package com.admin.service.impl;

import com.admin.common.dto.PanelUpdateSettingsRequest;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.config.EncryptionConfig;
import com.admin.entity.PanelUpdateSetting;
import com.admin.mapper.PanelUpdateSettingMapper;
import com.admin.service.PanelUpdateService;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Panel self-update settings and GitHub main-branch commit checks.
 *
 * The service only asks the host updater to perform an upgrade.  It never
 * executes Docker or Git commands inside the application container.
 */
@Service
@Slf4j
public class PanelUpdateServiceImpl implements PanelUpdateService {
    public static final int MIN_INTERVAL_SECONDS = 180;
    public static final int DEFAULT_INTERVAL_SECONDS = 86400;
    public static final String DEFAULT_REPOSITORY = "w243420707/flux-panel_rev";
    private static final long SETTING_ID = 1L;
    private static final String STATE_REQUEST = "request.json";
    private static final String STATE_RESULT = "result.json";
    private static final String STATE_TOKEN = "github-token";
    private static final Pattern REPOSITORY_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern COMMIT_PATTERN = Pattern.compile("^[a-fA-F0-9]{40}$");

    @Resource
    private PanelUpdateSettingMapper settingMapper;

    @Resource
    private RestTemplate restTemplate;

    @Value("${PANEL_UPDATE_REPOSITORY:${UPDATE_REPOSITORY:w243420707/flux-panel_rev}}")
    private String configuredRepository;

    @Value("${PANEL_VERSION:${APP_VERSION:1.3.107}}")
    private String configuredCurrentVersion;

    @Value("${PANEL_COMMIT:}")
    private String configuredCurrentCommit;

    @Value("${UPDATE_ENCRYPTION_KEY:${jwt-secret:}}")
    private String encryptionSecret;

    @Value("${UPDATE_STATE_DIR:/app/update-state}")
    private String stateDirectory;

    @Override
    public synchronized R getSettings() {
        PanelUpdateSetting setting = loadSetting();
        return R.ok(settingsView(setting));
    }

    @Override
    public synchronized R saveSettings(PanelUpdateSettingsRequest request) {
        if (request == null) {
            return R.err("更新设置不能为空");
        }

        PanelUpdateSetting setting = loadSetting();
        if (request.getEnabled() != null) {
            setting.setEnabled(request.getEnabled() ? 1 : 0);
        }
        if (request.getCheckIntervalSeconds() != null) {
            Integer interval = request.getCheckIntervalSeconds();
            if (interval < MIN_INTERVAL_SECONDS) {
                return R.err("自动检查间隔不能少于 180 秒");
            }
            setting.setCheckIntervalSeconds(interval);
        }

        if (Boolean.TRUE.equals(request.getClearGithubToken())) {
            setting.setGithubTokenEncrypted(null);
        } else if (request.getGithubToken() != null) {
            String token = request.getGithubToken().trim();
            if (token.isEmpty()) {
                setting.setGithubTokenEncrypted(null);
            } else {
                AESCrypto crypto = crypto();
                if (crypto == null) {
                    return R.err("服务端尚未配置更新加密密钥，暂时不能保存 GitHub Token");
                }
                setting.setGithubTokenEncrypted(crypto.encrypt(token));
            }
        }

        setting.setRepository(repository());
        setting.setCurrentVersion(currentVersion());
        setting.setCurrentCommit(currentCommit());
        setting.setNextCheckAt(nextCheckAt(setting));
        saveSetting(setting);
        return R.ok(settingsView(setting));
    }

    @Override
    public synchronized R checkNow() {
        return R.ok(checkLatestCommit(false));
    }

    @Override
    public synchronized R getStatus() {
        PanelUpdateSetting setting = loadSetting();
        Map<String, Object> status = statusView(setting);
        return R.ok(status);
    }

    /** Poll at a small fixed cadence; the persisted interval controls actual API calls. */
    @Scheduled(fixedDelayString = "${UPDATE_SCHEDULE_DELAY_MS:60000}", initialDelayString = "${UPDATE_SCHEDULE_INITIAL_DELAY_MS:60000}")
    public synchronized void scheduledCheck() {
        try {
            PanelUpdateSetting setting = loadSetting();
            if (setting.getEnabled() == null || setting.getEnabled() != 1) {
                return;
            }
            long now = System.currentTimeMillis();
            if (setting.getNextCheckAt() != null && setting.getNextCheckAt() > now) {
                return;
            }
            checkLatestCommit(true);
        } catch (Exception e) {
            // A missing database during startup must not stop Spring's scheduler.
            log.warn("自动检查面板更新失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> checkLatestCommit(boolean automatic) {
        PanelUpdateSetting setting = loadSetting();
        long now = System.currentTimeMillis();
        setting.setRepository(repository());
        setting.setCurrentVersion(currentVersion());
        setting.setCurrentCommit(currentCommit());
        setting.setNextCheckAt(now + intervalMillis(setting));

        try {
            if (!COMMIT_PATTERN.matcher(currentCommit()).matches()) {
                throw new IllegalStateException("当前部署缺少有效的 PANEL_COMMIT，请先在宿主机手动执行 deploy.sh update 更新面板，再启用自动更新");
            }
            String token = decryptToken(setting);
            JSONObject commit = fetchLatestCommit(setting.getRepository(), token);
            String sha = commit.getString("sha");
            if (!StringUtils.hasText(sha) || !COMMIT_PATTERN.matcher(sha.trim()).matches()) {
                throw new IllegalStateException("GitHub main 分支返回的 commit SHA 无效");
            }
            sha = sha.trim().toLowerCase();
            JSONObject commitDetails = commit.getJSONObject("commit");
            String message = commitDetails == null ? "" : commitDetails.getString("message");
            setting.setCurrentCommit(currentCommit());
            setting.setLatestCommit(sha);
            setting.setLatestCommitMessage(message == null ? "" : message.trim());
            setting.setLatestVersion(null);
            setting.setUpdateAvailable(isCommitUpdateAvailable(setting) ? 1 : 0);
            setting.setLastCheckAt(now);
            setting.setLastCheckStatus("SUCCESS");
            setting.setLastCheckMessage("main 分支检查完成");
            saveSetting(setting);

            if (automatic && setting.getEnabled() != null && setting.getEnabled() == 1
                    && setting.getUpdateAvailable() == 1) {
                enqueueIfNeeded(setting, sha, token, now);
            }
        } catch (Exception e) {
            setting.setUpdateAvailable(0);
            setting.setLastCheckAt(now);
            setting.setLastCheckStatus("FAILED");
            setting.setLastCheckMessage(safeMessage(e));
            saveSetting(setting);
            log.warn("检查 GitHub main 分支失败: {}", safeMessage(e));
        }
        return statusView(setting);
    }

    private JSONObject fetchLatestCommit(String repository, String token) {
        if (!REPOSITORY_PATTERN.matcher(repository).matches()) {
            throw new IllegalArgumentException("GitHub 仓库格式无效");
        }
        String endpoint = "https://api.github.com/repos/" + repository + "/commits/main";
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.USER_AGENT, "flux-panel-updater");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(endpoint, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException("GitHub API 返回 HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            throw new IllegalStateException("无法连接 GitHub API");
        }
        if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
            throw new IllegalStateException("GitHub API 返回空结果");
        }
        JSONObject commit = JSONObject.parseObject(response.getBody());
        if (commit == null) {
            throw new IllegalStateException("GitHub API 返回的 commit 为空");
        }
        return commit;
    }

    private void enqueueIfNeeded(PanelUpdateSetting setting, String commit, String token, long now) {
        Path directory = Path.of(stateDirectory);
        // The host owns a published request until it consumes it, including retries.
        // Never replace its request ID or credential while an update is pending/running.
        if (Files.exists(directory.resolve(STATE_REQUEST))) {
            return;
        }
        if (commit.equalsIgnoreCase(setting.getUpdateRequestTag())
                && setting.getUpdateRequestedAt() != null
                && now - setting.getUpdateRequestedAt() < intervalMillis(setting)) {
            return;
        }
        String requestId = UUID.randomUUID().toString();
        try {
            Files.createDirectories(directory);
            if (StringUtils.hasText(token)) {
                writeRestricted(directory.resolve(STATE_TOKEN), token);
            } else {
                Files.deleteIfExists(directory.resolve(STATE_TOKEN));
            }
            JSONObject request = new JSONObject();
            request.put("requestId", requestId);
            request.put("repository", setting.getRepository());
            request.put("tag", "main");
            request.put("commit", commit);
            request.put("requestedAt", now);
            writeAtomic(directory.resolve(STATE_REQUEST), request.toJSONString());
            setting.setUpdateRequestId(requestId);
            setting.setUpdateRequestTag(commit);
            setting.setUpdateRequestedAt(now);
            saveSetting(setting);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入宿主机更新请求");
        }
    }

    private Map<String, Object> settingsView(PanelUpdateSetting setting) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", setting.getEnabled() != null && setting.getEnabled() == 1);
        result.put("checkIntervalSeconds", intervalSeconds(setting));
        result.put("minCheckIntervalSeconds", MIN_INTERVAL_SECONDS);
        result.put("repository", setting.getRepository());
        result.put("githubTokenConfigured", StringUtils.hasText(setting.getGithubTokenEncrypted()));
        result.put("githubTokenMasked", maskToken(decryptTokenSafely(setting)));
        return result;
    }

    private Map<String, Object> statusView(PanelUpdateSetting setting) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentVersion", currentVersion());
        result.put("latestVersion", setting.getLatestVersion());
        result.put("currentCommit", currentCommit());
        result.put("latestCommit", setting.getLatestCommit());
        result.put("latestCommitMessage", setting.getLatestCommitMessage());
        result.put("updateAvailable", isCommitUpdateAvailable(setting));
        result.put("lastCheckAt", setting.getLastCheckAt());
        result.put("lastCheckStatus", setting.getLastCheckStatus());
        result.put("lastCheckMessage", setting.getLastCheckMessage());
        result.put("nextCheckAt", setting.getNextCheckAt());
        result.put("repository", setting.getRepository());
        result.put("updateRequestId", setting.getUpdateRequestId());
        result.put("updateRequestTag", setting.getUpdateRequestTag());
        mergeHostResult(result);
        return result;
    }

    private void mergeHostResult(Map<String, Object> status) {
        Path resultPath = Path.of(stateDirectory).resolve(STATE_RESULT);
        if (!Files.isRegularFile(resultPath)) {
            status.put("updaterStatus", "NOT_READY");
            status.put("updaterReady", false);
            status.put("updateStatus", "NOT_READY");
            status.put("updateMessage", "宿主机更新服务尚未返回状态");
            return;
        }
        try {
            String raw = Files.readString(resultPath, StandardCharsets.UTF_8);
            JSONObject result = JSONObject.parseObject(raw);
            if (result == null) {
                status.put("updaterStatus", "UNKNOWN");
                status.put("updaterReady", false);
                status.put("updateStatus", "UNKNOWN");
                status.put("updateMessage", "宿主机状态文件格式无效");
                return;
            }
            String resultRequestId = result.getString("requestId");
            Object pendingRequestId = status.get("updateRequestId");
            if (pendingRequestId != null && !pendingRequestId.equals(resultRequestId)) {
                status.put("updaterStatus", "PENDING");
                status.put("updaterMessage", "等待宿主机执行更新");
                status.put("updaterReady", true);
                status.put("updateStatus", "PENDING");
                status.put("updateMessage", "等待宿主机执行更新");
                return;
            }
            status.put("updaterStatus", result.getString("status"));
            status.put("updaterMessage", result.getString("message"));
            status.put("updaterReady", true);
            status.put("updateStatus", result.getString("status"));
            status.put("updateMessage", result.getString("message"));
            status.put("updaterFinishedAt", result.getLong("finishedAt"));
            if (StringUtils.hasText(result.getString("currentVersion"))) {
                status.put("currentVersion", result.getString("currentVersion"));
            }
            if (StringUtils.hasText(result.getString("latestVersion"))) {
                status.put("latestVersion", result.getString("latestVersion"));
            }
        } catch (Exception e) {
            status.put("updaterStatus", "INVALID");
            status.put("updaterReady", false);
            status.put("updateStatus", "INVALID");
            status.put("updateMessage", "宿主机状态文件无法读取");
        }
    }

    private PanelUpdateSetting loadSetting() {
        PanelUpdateSetting setting = settingMapper.selectById(SETTING_ID);
        if (setting == null) {
            setting = new PanelUpdateSetting();
            setting.setId(SETTING_ID);
            setting.setEnabled(0);
            setting.setCheckIntervalSeconds(DEFAULT_INTERVAL_SECONDS);
            setting.setRepository(repository());
            setting.setCurrentVersion(currentVersion());
            setting.setUpdateAvailable(0);
            setting.setLastCheckStatus("NEVER");
            settingMapper.insert(setting);
        }
        boolean changed = false;
        if (!StringUtils.hasText(setting.getRepository()) || !repository().equals(setting.getRepository())) {
            setting.setRepository(repository());
            changed = true;
        }
        if (!StringUtils.hasText(setting.getCurrentVersion())) {
            setting.setCurrentVersion(currentVersion());
            changed = true;
        }
        if (!StringUtils.hasText(setting.getCurrentCommit()) && StringUtils.hasText(currentCommit())) {
            setting.setCurrentCommit(currentCommit());
            changed = true;
        }
        if (setting.getCheckIntervalSeconds() == null || setting.getCheckIntervalSeconds() < MIN_INTERVAL_SECONDS) {
            setting.setCheckIntervalSeconds(DEFAULT_INTERVAL_SECONDS);
            changed = true;
        }
        if (setting.getLastCheckStatus() == null) {
            setting.setLastCheckStatus("NEVER");
            changed = true;
        }
        if (changed) {
            settingMapper.updateById(setting);
        }
        return setting;
    }

    private void saveSetting(PanelUpdateSetting setting) {
        settingMapper.updateById(setting);
    }

    private String repository() {
        String value = configuredRepository == null ? "" : configuredRepository.trim();
        if (value.startsWith("https://github.com/") || value.startsWith("http://github.com/")) {
            value = value.substring(value.indexOf("github.com/") + "github.com/".length());
        }
        if (value.endsWith(".git")) {
            value = value.substring(0, value.length() - 4);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return REPOSITORY_PATTERN.matcher(value).matches() ? value : DEFAULT_REPOSITORY;
    }

    private String currentVersion() {
        return StringUtils.hasText(configuredCurrentVersion) ? configuredCurrentVersion.trim() : "1.3.107";
    }

    private String currentCommit() {
        return StringUtils.hasText(configuredCurrentCommit) ? configuredCurrentCommit.trim().toLowerCase() : "";
    }

    private boolean isCommitUpdateAvailable(PanelUpdateSetting setting) {
        return COMMIT_PATTERN.matcher(currentCommit()).matches()
                && StringUtils.hasText(setting.getLatestCommit())
                && COMMIT_PATTERN.matcher(setting.getLatestCommit()).matches()
                && !setting.getLatestCommit().equalsIgnoreCase(currentCommit());
    }

    private int intervalSeconds(PanelUpdateSetting setting) {
        return setting.getCheckIntervalSeconds() == null
                ? DEFAULT_INTERVAL_SECONDS : Math.max(MIN_INTERVAL_SECONDS, setting.getCheckIntervalSeconds());
    }

    private long intervalMillis(PanelUpdateSetting setting) {
        return intervalSeconds(setting) * 1000L;
    }

    private Long nextCheckAt(PanelUpdateSetting setting) {
        return System.currentTimeMillis() + intervalMillis(setting);
    }

    private AESCrypto crypto() {
        return EncryptionConfig.getOrCreateCrypto(encryptionSecret);
    }

    private String decryptToken(PanelUpdateSetting setting) {
        if (!StringUtils.hasText(setting.getGithubTokenEncrypted())) {
            return null;
        }
        AESCrypto aes = crypto();
        if (aes == null) {
            throw new IllegalStateException("更新加密密钥不可用");
        }
        try {
            return aes.decryptString(setting.getGithubTokenEncrypted());
        } catch (Exception e) {
            throw new IllegalStateException("GitHub Token 无法解密，请重新保存");
        }
    }

    private String decryptTokenSafely(PanelUpdateSetting setting) {
        try {
            return decryptToken(setting);
        } catch (Exception e) {
            return null;
        }
    }

    private String maskToken(String token) {
        if (!StringUtils.hasText(token)) return "";
        if (token.length() <= 8) return "********";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    private void writeRestricted(Path path, String value) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.deleteIfExists(temp);
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(permissions));
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(temp);
        }
        try {
            Files.writeString(temp, value, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(temp, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows development environments do not expose POSIX permissions.
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void writeAtomic(Path path, String value) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return StringUtils.hasText(message) ? message.substring(0, Math.min(1000, message.length())) : "检查失败";
    }
}
