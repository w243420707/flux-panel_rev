package com.admin.controller;

import cn.hutool.core.util.IdUtil;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.ProbeNodeReportDto;
import com.admin.common.dto.ProbeSyncReportDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.ViteConfig;
import com.admin.service.NodeService;
import com.admin.service.NodeWallMonitorService;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.*;

@RestController
@CrossOrigin
public class ProbeSyncController {

    private static final String API_KEY_CONFIG = "android_probe_api_key";
    private static final String API_KEY_HEADER = "X-Flux-Probe-Key";

    @Resource
    private ViteConfigService viteConfigService;

    @Resource
    private NodeService nodeService;

    @Resource
    private NodeWallMonitorService nodeWallMonitorService;

    @RequireRole
    @PostMapping("/api/v1/probe-sync/key")
    public R getApiKey() {
        String key = getOrCreateApiKey();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiKey", key);
        data.put("nodesUrl", "/api/probe-sync/v1/nodes");
        data.put("reportUrl", "/api/probe-sync/v1/report");
        return R.ok(data);
    }

    @RequireRole
    @PostMapping("/api/v1/probe-sync/key/rotate")
    public R rotateApiKey() {
        String key = "fp_" + IdUtil.fastSimpleUUID();
        saveApiKey(key);
        return getApiKey();
    }

    @GetMapping("/api/probe-sync/v1/nodes")
    public R listNodes(@RequestHeader(value = API_KEY_HEADER, required = false) String headerKey,
                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!isValidApiKey(resolveApiKey(headerKey, authorization))) {
            return R.err(401, "探针 API Key 无效");
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Node> sourceNodes = nodeService.list(new QueryWrapper<Node>()
                .select("id", "name", "server_ip", "server_ipv4", "server_ipv6", "change_ip_min_interval_minutes", "change_ip_remote_api", "status", "updated_time"));
        for (Node node : sourceNodes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", node.getId());
            item.put("name", node.getName());
            item.put("host", resolveProbeHost(node));
            item.put("ports", "22");
            item.put("changeIpMinIntervalMinutes", node.getChangeIpMinIntervalMinutes());
            item.put("changeIpRemoteApi", normalize(node.getChangeIpRemoteApi()));
            item.put("status", node.getStatus());
            item.put("updatedAt", node.getUpdatedTime());
            nodes.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", System.currentTimeMillis());
        data.put("nodes", nodes);
        return R.ok(data);
    }

    @PostMapping("/api/probe-sync/v1/report")
    public R report(@RequestHeader(value = API_KEY_HEADER, required = false) String headerKey,
                    @RequestHeader(value = "Authorization", required = false) String authorization,
                    @RequestBody ProbeSyncReportDto report) {
        if (!isValidApiKey(resolveApiKey(headerKey, authorization))) {
            return R.err(401, "探针 API Key 无效");
        }
        if (report == null || report.getResults() == null || report.getResults().size() > 1000) {
            return R.err("探针上报数据无效");
        }

        int accepted = 0;
        int ignored = 0;
        for (ProbeNodeReportDto result : report.getResults()) {
            if (result == null || result.getNodeId() == null || result.getReachable() == null) {
                ignored++;
                continue;
            }
            String message = trimMessage(result.getMessage());
            R markResult;
            if (Boolean.TRUE.equals(result.getReachable())) {
                markResult = nodeWallMonitorService.markNodeAvailableByExternalProbe(result.getNodeId(), message);
            } else {
                markResult = nodeWallMonitorService.markNodeUnavailableByExternalProbe(result.getNodeId(), message);
            }
            if (markResult.getCode() != 0) {
                ignored++;
                continue;
            }
            accepted++;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", accepted);
        data.put("ignored", ignored);
        data.put("serverTime", System.currentTimeMillis());
        return R.ok(data);
    }

    private String getOrCreateApiKey() {
        ViteConfig config = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", API_KEY_CONFIG));
        if (config != null && StringUtils.hasText(config.getValue())) {
            return config.getValue().trim();
        }
        String key = "fp_" + IdUtil.fastSimpleUUID();
        saveApiKey(key);
        return key;
    }

    private void saveApiKey(String key) {
        ViteConfig config = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", API_KEY_CONFIG));
        if (config == null) {
            config = new ViteConfig();
            config.setName(API_KEY_CONFIG);
            config.setValue(key);
            config.setTime(System.currentTimeMillis());
            viteConfigService.save(config);
        } else {
            config.setValue(key);
            config.setTime(System.currentTimeMillis());
            viteConfigService.updateById(config);
        }
    }

    private String resolveApiKey(String headerKey, String authorization) {
        if (StringUtils.hasText(headerKey)) {
            return headerKey.trim();
        }
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7).trim();
        }
        return authorization == null ? "" : authorization.trim();
    }

    private boolean isValidApiKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        ViteConfig config = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", API_KEY_CONFIG));
        return config != null && Objects.equals(key.trim(), config.getValue());
    }

    private String resolveProbeHost(Node node) {
        String serverIp = normalize(node.getServerIp());
        if (StringUtils.hasText(serverIp) && !isIpLiteral(serverIp)) {
            return serverIp;
        }
        if (StringUtils.hasText(normalize(node.getServerIpv4()))) {
            return normalize(node.getServerIpv4());
        }
        if (StringUtils.hasText(serverIp)) {
            return serverIp;
        }
        if (StringUtils.hasText(normalize(node.getServerIpv6()))) {
            return normalize(node.getServerIpv6());
        }
        return normalize(node.getIp());
    }

    private boolean isIpLiteral(String value) {
        return value.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$") || value.contains(":");
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String trimMessage(String value) {
        if (!StringUtils.hasText(value)) {
            return "探针未提供检测说明";
        }
        String normalized = value.trim();
        return normalized.length() > 1000 ? normalized.substring(0, 1000) : normalized;
    }
}
