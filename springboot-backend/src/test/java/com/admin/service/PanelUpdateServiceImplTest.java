package com.admin.service;

import com.admin.common.dto.PanelUpdateSettingsRequest;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.entity.PanelUpdateSetting;
import com.admin.mapper.PanelUpdateSettingMapper;
import com.admin.service.impl.PanelUpdateServiceImpl;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PanelUpdateServiceImplTest {
    private static final String ENCRYPTION_KEY = "panel-update-unit-test-key";
    private PanelUpdateSettingMapper mapper;
    private RestTemplate restTemplate;
    private PanelUpdateServiceImpl service;
    private PanelUpdateSetting setting;
    private MockRestServiceServer github;

    @BeforeEach
    void setUp(@TempDir Path stateDirectory) {
        mapper = mock(PanelUpdateSettingMapper.class);
        restTemplate = new RestTemplate();
        github = MockRestServiceServer.createServer(restTemplate);
        service = new PanelUpdateServiceImpl();
        ReflectionTestUtils.setField(service, "settingMapper", mapper);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "configuredRepository", "owner/private-panel");
        ReflectionTestUtils.setField(service, "configuredCurrentVersion", "1.0.17");
        ReflectionTestUtils.setField(service, "encryptionSecret", ENCRYPTION_KEY);
        ReflectionTestUtils.setField(service, "stateDirectory", stateDirectory.toString());

        setting = new PanelUpdateSetting();
        setting.setId(1L);
        setting.setEnabled(0);
        setting.setCheckIntervalSeconds(86400);
        setting.setRepository("owner/private-panel");
        setting.setCurrentVersion("1.0.17");
        setting.setUpdateAvailable(0);
        setting.setLastCheckStatus("NEVER");
        when(mapper.selectById(1L)).thenReturn(setting);
    }

    @Test
    void settingsNeverReturnPlaintextToken() {
        setting.setGithubTokenEncrypted(new AESCrypto(ENCRYPTION_KEY).encrypt("github_pat_secret-value"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) service.getSettings().getData();

        assertEquals(true, data.get("githubTokenConfigured"));
        assertEquals("gith****alue", data.get("githubTokenMasked"));
        assertFalse(data.containsValue("github_pat_secret-value"));
    }

    @Test
    void intervalBelowThreeMinutesIsRejected() {
        PanelUpdateSettingsRequest request = new PanelUpdateSettingsRequest();
        request.setCheckIntervalSeconds(179);

        R response = service.saveSettings(request);

        assertNotEquals(0, response.getCode());
        verify(mapper, never()).updateById(any(PanelUpdateSetting.class));
    }

    @Test
    void commitCheckUsesBearerTokenAndIgnoresSemver() {
        String currentCommit = "a".repeat(40);
        String latestCommit = "b".repeat(40);
        setting.setGithubTokenEncrypted(new AESCrypto(ENCRYPTION_KEY).encrypt("github_pat_secret-value"));
        setting.setCurrentVersion("99.0.0");
        ReflectionTestUtils.setField(service, "configuredCurrentCommit", currentCommit);
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/commits/main"))
                .andExpect(header("Authorization", "Bearer github_pat_secret-value"))
                .andRespond(withSuccess(commitResponse(latestCommit, "Fix panel startup"), MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) service.checkNow().getData();

        github.verify();
        assertEquals("1.0.17", status.get("currentVersion"));
        assertEquals(currentCommit, status.get("currentCommit"));
        assertEquals(latestCommit, status.get("latestCommit"));
        assertEquals("Fix panel startup", status.get("latestCommitMessage"));
        assertEquals(true, status.get("updateAvailable"));
        assertEquals("SUCCESS", status.get("lastCheckStatus"));
    }

    @Test
    void equalCommitIsNotAnUpdateEvenWhenLegacyVersionsDiffer() {
        String currentCommit = "c".repeat(40);
        setting.setLatestVersion("v999.0.0");
        setting.setLatestCommit(currentCommit);
        ReflectionTestUtils.setField(service, "configuredCurrentCommit", currentCommit);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) service.getStatus().getData();

        assertEquals(false, status.get("updateAvailable"));
    }

    @Test
    void unknownCurrentCommitTriggersOneBootstrapUpdate() {
        setting.setLatestCommit("f".repeat(40));
        ReflectionTestUtils.setField(service, "configuredCurrentCommit", "");

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) service.getStatus().getData();

        assertEquals(true, status.get("updateAvailable"));
    }

    @Test
    void automaticCheckQueuesMainAndStoresCommitShaAsRequestTag() throws Exception {
        String currentCommit = "d".repeat(40);
        String latestCommit = "e".repeat(40);
        ReflectionTestUtils.setField(service, "configuredCurrentCommit", currentCommit);
        setting.setEnabled(1);
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/commits/main"))
                .andRespond(withSuccess(commitResponse(latestCommit, "Update API"), MediaType.APPLICATION_JSON));

        ReflectionTestUtils.invokeMethod(service, "checkLatestCommit", true);

        github.verify();
        Path requestPath = Path.of(ReflectionTestUtils.getField(service, "stateDirectory").toString(), "request.json");
        JSONObject request = JSONObject.parseObject(Files.readString(requestPath));
        assertEquals("main", request.getString("tag"));
        assertEquals(latestCommit, request.getString("commit"));
        assertEquals(latestCommit, setting.getUpdateRequestTag());
        assertNotNull(setting.getUpdateRequestId());
    }

    private static String commitResponse(String sha, String message) {
        JSONObject commit = new JSONObject();
        commit.put("sha", sha);
        JSONObject details = new JSONObject();
        details.put("message", message);
        commit.put("commit", details);
        return commit.toJSONString();
    }
}
