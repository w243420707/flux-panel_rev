package com.admin.service;

import com.admin.common.dto.PanelUpdateSettingsRequest;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.entity.PanelUpdateSetting;
import com.admin.mapper.PanelUpdateSettingMapper;
import com.admin.service.impl.PanelUpdateServiceImpl;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class PanelUpdateServiceImplTest {
    private static final String ENCRYPTION_KEY = "panel-update-unit-test-key";
    private static final String CURRENT_COMMIT = "a".repeat(40);
    private PanelUpdateSettingMapper mapper;
    private RestTemplate restTemplate;
    private PanelUpdateServiceImpl service;
    private PanelUpdateSetting setting;
    private MockRestServiceServer github;
    private Path stateDirectory;

    @BeforeEach
    void setUp(@TempDir Path stateDirectory) {
        this.stateDirectory = stateDirectory;
        mapper = mock(PanelUpdateSettingMapper.class);
        restTemplate = new RestTemplate();
        github = MockRestServiceServer.createServer(restTemplate);
        service = new PanelUpdateServiceImpl();
        ReflectionTestUtils.setField(service, "settingMapper", mapper);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "configuredRepository", "owner/private-panel");
        ReflectionTestUtils.setField(service, "configuredCurrentVersion", "1.0.17");
        ReflectionTestUtils.setField(service, "configuredCurrentCommit", CURRENT_COMMIT);
        ReflectionTestUtils.setField(service, "encryptionSecret", ENCRYPTION_KEY);
        ReflectionTestUtils.setField(service, "stateDirectory", stateDirectory.toString());

        setting = new PanelUpdateSetting();
        setting.setId(1L);
        setting.setEnabled(0);
        setting.setCheckIntervalSeconds(86400);
        setting.setRepository("owner/private-panel");
        setting.setCurrentVersion("1.0.17");
        setting.setCurrentCommit(CURRENT_COMMIT);
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
        setting.setEnabled(1);
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
        assertFalse(Files.exists(stateDirectory.resolve("request.json")));
        assertFalse(Files.exists(stateDirectory.resolve("github-token")));
    }

    @Test
    void equalCommitIsNotAnUpdateEvenWhenLegacyVersionsDiffer() {
        String currentCommit = "c".repeat(40);
        setting.setLatestVersion("v999.0.0");
        setting.setLatestCommit(currentCommit);
        ReflectionTestUtils.setField(service, "configuredCurrentCommit", currentCommit);
        setting.setEnabled(1);
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/commits/main"))
                .andRespond(withSuccess(commitResponse(currentCommit, "Already deployed"), MediaType.APPLICATION_JSON));

        service.scheduledCheck();
        github.verify();

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) service.getStatus().getData();

        assertEquals(false, status.get("updateAvailable"));
        assertEquals("SUCCESS", setting.getLastCheckStatus());
        assertFalse(Files.exists(stateDirectory.resolve("request.json")));
        assertNull(setting.getUpdateRequestId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"main", "abc123", "not-a-commit",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "gggggggggggggggggggggggggggggggggggggggg"})
    void missingOrInvalidCurrentCommitFailsWithoutFetchingOrQueuing(String currentCommit) {
        setting.setLatestCommit("f".repeat(40));
        setting.setEnabled(1);
        ReflectionTestUtils.setField(service, "configuredCurrentCommit", currentCommit);

        service.scheduledCheck();
        setting.setNextCheckAt(0L);
        service.scheduledCheck();
        github.verify();

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) service.getStatus().getData();

        assertEquals(false, status.get("updateAvailable"));
        assertEquals("FAILED", status.get("lastCheckStatus"));
        assertTrue(status.get("lastCheckMessage").toString().contains("PANEL_COMMIT"));
        assertTrue(status.get("lastCheckMessage").toString().contains("手动执行 deploy.sh update"));
        assertFalse(Files.exists(stateDirectory.resolve("request.json")));
        assertFalse(Files.exists(stateDirectory.resolve("github-token")));
        assertNull(setting.getUpdateRequestId());
    }

    @Test
    void automaticCheckQueuesMainAndStoresCommitShaAsRequestTag() throws Exception {
        String currentCommit = "d".repeat(40);
        String latestCommit = "e".repeat(40);
        ReflectionTestUtils.setField(service, "configuredCurrentCommit", currentCommit);
        setting.setEnabled(1);
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/commits/main"))
                .andRespond(withSuccess(commitResponse(latestCommit, "Update API"), MediaType.APPLICATION_JSON));

        service.scheduledCheck();

        github.verify();
        Path requestPath = Path.of(ReflectionTestUtils.getField(service, "stateDirectory").toString(), "request.json");
        JSONObject request = JSONObject.parseObject(Files.readString(requestPath));
        assertEquals("main", request.getString("tag"));
        assertEquals(latestCommit, request.getString("commit"));
        assertEquals(latestCommit, setting.getUpdateRequestTag());
        assertNotNull(setting.getUpdateRequestId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "cccccccccccccccccccccccccccccccccccccccc"})
    void laterDueTickKeepsPendingRequestAndTokenEvenForANewerRemoteCommit(String nextCommit) throws Exception {
        String queuedCommit = "b".repeat(40);
        setting.setEnabled(1);
        setting.setGithubTokenEncrypted(new AESCrypto(ENCRYPTION_KEY).encrypt("github_pat_original"));
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/commits/main"))
                .andExpect(header("Authorization", "Bearer github_pat_original"))
                .andRespond(withSuccess(commitResponse(queuedCommit, "First update"), MediaType.APPLICATION_JSON));
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/commits/main"))
                .andExpect(header("Authorization", "Bearer github_pat_replacement"))
                .andRespond(withSuccess(commitResponse(nextCommit, "Later check"), MediaType.APPLICATION_JSON));

        service.scheduledCheck();
        String originalRequest = Files.readString(stateDirectory.resolve("request.json"));
        String originalRequestId = setting.getUpdateRequestId();
        PanelUpdateSettingsRequest changeToken = new PanelUpdateSettingsRequest();
        changeToken.setGithubToken("github_pat_replacement");
        assertEquals(0, service.saveSettings(changeToken).getCode());
        setting.setUpdateRequestedAt(0L);
        setting.setNextCheckAt(0L);

        service.scheduledCheck();

        github.verify();
        assertEquals(originalRequest, Files.readString(stateDirectory.resolve("request.json")));
        assertEquals("github_pat_original", Files.readString(stateDirectory.resolve("github-token")));
        assertEquals(originalRequestId, setting.getUpdateRequestId());
        assertEquals(queuedCommit, setting.getUpdateRequestTag());
        assertEquals(nextCommit, setting.getLatestCommit());
    }

    @Test
    void apiFailureDoesNotQueueAStaleUpdate() {
        setting.setEnabled(1);
        setting.setLatestCommit("b".repeat(40));
        setting.setUpdateAvailable(1);
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/commits/main"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        service.scheduledCheck();

        github.verify();
        assertEquals("FAILED", setting.getLastCheckStatus());
        assertEquals("GitHub API 返回 HTTP 503", setting.getLastCheckMessage());
        assertFalse(Files.exists(stateDirectory.resolve("request.json")));
        assertNull(setting.getUpdateRequestId());
    }

    @Test
    void settingsSaveWaitsForInFlightCheckAndKeepsItsNewValues() throws Exception {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch finishCheck = new CountDownLatch(1);
        CountDownLatch saving = new CountDownLatch(1);
        github.expect(requestTo("https://api.github.com/repos/owner/private-panel/commits/main"))
                .andRespond(request -> {
                    checking.countDown();
                    try {
                        if (!finishCheck.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("check timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(e);
                    }
                    return withSuccess(commitResponse(CURRENT_COMMIT, "No update"), MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var check = pool.submit(() -> service.checkNow());
            assertTrue(checking.await(5, TimeUnit.SECONDS));
            PanelUpdateSettingsRequest request = new PanelUpdateSettingsRequest();
            request.setEnabled(true);
            request.setCheckIntervalSeconds(180);
            var save = pool.submit(() -> {
                saving.countDown();
                return service.saveSettings(request);
            });
            assertTrue(saving.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> save.get(100, TimeUnit.MILLISECONDS));
            finishCheck.countDown();
            assertEquals(0, check.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(0, save.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(1, setting.getEnabled());
            assertEquals(180, setting.getCheckIntervalSeconds());
            github.verify();
        } finally {
            finishCheck.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void requestDebugStringDoesNotExposeGithubToken() {
        PanelUpdateSettingsRequest request = new PanelUpdateSettingsRequest();
        request.setGithubToken("github_pat_do-not-log");
        assertFalse(request.toString().contains("github_pat_do-not-log"));
    }

    @Test
    void clearingTokenPersistsNullThroughRealMybatisMapper() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:panel_update_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE panel_update_setting (
                  id BIGINT PRIMARY KEY, enabled INT, check_interval_seconds INT, github_token_encrypted CLOB,
                  repository VARCHAR(255), current_version VARCHAR(64), latest_version VARCHAR(64),
                  current_commit VARCHAR(64), latest_commit VARCHAR(64), latest_commit_message CLOB,
                  update_available INT, last_check_at BIGINT, last_check_status VARCHAR(32),
                  last_check_message VARCHAR(1000), next_check_at BIGINT, update_request_id VARCHAR(64),
                  update_request_tag VARCHAR(64), update_requested_at BIGINT
                )
                """);
        MybatisSqlSessionFactoryBean builder = new MybatisSqlSessionFactoryBean();
        builder.setDataSource(source);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        builder.setConfiguration(configuration);
        var factory = builder.getObject();
        assertNotNull(factory);
        factory.getConfiguration().addMapper(PanelUpdateSettingMapper.class);
        PanelUpdateSettingMapper realMapper = new SqlSessionTemplate(factory).getMapper(PanelUpdateSettingMapper.class);
        realMapper.insert(setting);
        ReflectionTestUtils.setField(service, "settingMapper", realMapper);

        PanelUpdateSettingsRequest save = new PanelUpdateSettingsRequest();
        save.setGithubToken("github_pat_database-test");
        assertEquals(0, service.saveSettings(save).getCode());
        assertNotNull(jdbc.queryForObject("SELECT github_token_encrypted FROM panel_update_setting WHERE id=1", String.class));
        PanelUpdateSettingsRequest clear = new PanelUpdateSettingsRequest();
        clear.setClearGithubToken(true);
        assertEquals(0, service.saveSettings(clear).getCode());

        assertNull(jdbc.queryForObject("SELECT github_token_encrypted FROM panel_update_setting WHERE id=1", String.class));
        assertNull(realMapper.selectById(1L).getGithubTokenEncrypted());
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
