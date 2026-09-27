package com.admin.service;

import com.admin.common.dto.PanelUpdateSettingsRequest;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.entity.PanelUpdateSetting;
import com.admin.mapper.PanelUpdateSettingMapper;
import com.admin.service.impl.PanelUpdateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
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
    void releaseCheckUsesBearerTokenAndFindsNewerVersion() {
        setting.setGithubTokenEncrypted(new AESCrypto(ENCRYPTION_KEY).encrypt("github_pat_secret-value"));
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/releases/latest"))
                .andExpect(header("Authorization", "Bearer github_pat_secret-value"))
                .andRespond(withSuccess("{\"tag_name\":\"v1.0.18\"}", MediaType.APPLICATION_JSON));

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) service.checkNow().getData();

        github.verify();
        assertEquals("v1.0.18", status.get("latestVersion"));
        assertEquals(true, status.get("updateAvailable"));
        assertEquals("SUCCESS", status.get("lastCheckStatus"));
    }
}
