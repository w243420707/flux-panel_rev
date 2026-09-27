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
 * Panel self-update settings and GitHub Release checks.
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
    private static final Pattern RELEASE_TAG_PATTERN = Pattern.compile("^v\\d+\\.\\d+\\.\\d+$");

    @Resource
    private PanelUpdateSettingMapper settingMapper;

    @Resource
    private RestTemplate restTemplate;

    @Value("${PANEL_UPDATE_REPOSITORY:${UPDATE_REPOSITORY:w243420707/flux-panel_rev}}")
    private String configuredRepository;

    @Value("${PANEL_VERSION:${APP_VERSION:1.3.88}}")
    private String configuredCurrentVersion;

    @Value("${UPDATE_ENCRYPTION_KEY:${jwt-secret:}}")
    private String encryptionSecret;

    @Value("${UPDATE_STATE_DIR:/app/update-state}")
    private String stateDirectory;

    @Override
    public R getSettings() {
        PanelUpdateSetting setting = loadSetting();
        return R.ok(settingsView(setting));
    }

    @Override
    public R saveSettings(PanelUpdateSettingsRequest request) {
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
        setting.setNextCheckAt(nextCheckAt(setting));
        saveSetting(setting);
        return R.ok(settingsView(setting));
    }

    @Override
    public R checkNow() {
        return R.ok(checkRelease(false));
    }

    @Override
    public R getStatus() {
        PanelUpdateSetting setting = loadSetting();
        Map<String, Object> status = statusView(setting);
        return R.ok(status);
    }

    /** Poll at a small fixed cadence; the persisted interval controls actual API calls. */
    @Scheduled(fixedDelayString = "${UPDATE_SCHEDULE_DELAY_MS:60000}", initialDelayString = "${UPDATE_SCHEDULE_INITIAL_DELAY_MS:60000}")
    public void scheduledCheck() {
        try {
            PanelUpdateSetting setting = loadSetting();
            if (setting.getEnabled() == null || setting.getEnabled() != 1) {
                return;
            }
            long now = System.currentTimeMillis();
            if (setting.getNextCheckAt() != null && setting.getNextCheckAt() > now) {
                return;
            }
            checkRelease(true);
        } catch (Exception e) {
            // A missing database during startup must not stop Spring's scheduler.
            log.warn("自动检查面板更新失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> checkRelease(boolean automatic) {
        PanelUpdateSetting setting = loadSetting();
        long now = System.currentTimeMillis();
        setting.setRepository(repository());
        setting.setCurrentVersion(currentVersion());
        setting.setNextCheckAt(now + intervalMillis(setting));

        try {
            String token = decryptToken(setting);
            String release = fetchLatestRelease(setting.getRepository(), token);
            JSONObject releaseJson = JSONObject.parseObject(release);
            String tag = releaseJson == null ? null : releaseJson.getString("tag_name");
            if (!StringUtils.hasText(tag) || !RELEASE_TAG_PATTERN.matcher(tag.trim()).matches()) {
                throw new IllegalStateException("GitHub 最新 Release 标签不是 vX.Y.Z 格式");
            }
            tag = tag.trim();
            setting.setLatestVersion(tag);
            setting.setUpdateAvailable(compareVersions(tag, setting.getCurrentVersion()) > 0 ? 1 : 0);
            setting.setLastCheckAt(now);
            setting.setLastCheckStatus("SUCCESS");
            setting.setLastCheckMessage("检查完成");
            saveSetting(setting);

            if (automatic && setting.getEnabled() != null && setting.getEnabled() == 1
                    && setting.getUpdateAvailable() == 1) {
                enqueueIfNeeded(setting, tag, token, now);
            }
        } catch (Exception e) {
            setting.setLastCheckAt(now);
            setting.setLastCheckStatus("FAILED");
            setting.setLastCheckMessage(safeMessage(e));
            saveSetting(setting);
            log.warn("检查 GitHub Release 失败: {}", safeMessage(e));
        }
        return statusView(setting);
    }

    private String fetchLatestRelease(String repository, String token) {
        if (!REPOSITORY_PATTERN.matcher(repository).matches()) {
            throw new IllegalArgumentException("GitHub 仓库格式无效");
        }
        String endpoint = "https://api.github.com/repos/" + repository + "/releases/latest";
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
        return response.getBody();
    }

    private void enqueueIfNeeded(PanelUpdateSetting setting, String tag, String token, long now) {
        if (tag.equals(setting.getUpdateRequestTag())
                && setting.getUpdateRequestedAt() != null
                && now - setting.getUpdateRequestedAt() < intervalMillis(setting)) {
            return;
        }
        String requestId = UUID.randomUUID().toString();
        Path directory = Path.of(stateDirectory);
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
            request.put("tag", tag);
            request.put("requestedAt", now);
            writeAtomic(directory.resolve(STATE_REQUEST), request.toJSONString());
            setting.setUpdateRequestId(requestId);
            setting.setUpdateRequestTag(tag);
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
        result.put("updateAvailable", StringUtils.hasText(setting.getLatestVersion())
                && compareVersions(setting.getLatestVersion(), currentVersion()) > 0);
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
        return StringUtils.hasText(configuredCurrentVersion) ? configuredCurrentVersion.trim() : "1.3.88";
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

    private int compareVersions(String left, String right) {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    private int[] versionParts(String value) {
        int[] result = new int[]{0, 0, 0};
        if (!StringUtils.hasText(value)) return result;
        String normalized = value.trim().replaceFirst("^v", "");
        String[] parts = normalized.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try { result[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) { return new int[]{0, 0, 0}; }
        }
        return result;
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
