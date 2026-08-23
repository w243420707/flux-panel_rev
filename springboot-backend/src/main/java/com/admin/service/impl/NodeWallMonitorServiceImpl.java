package com.admin.service.impl;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.service.CloudflareDnsSyncService;
import com.admin.service.NodeService;
import com.admin.service.NodeWallMonitorService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class NodeWallMonitorServiceImpl implements NodeWallMonitorService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_OK = "OK";
    private static final String STATUS_OBSERVING = "OBSERVING";
    private static final String STATUS_SUSPECTED_BLOCKED = "SUSPECTED_BLOCKED";
    private static final String STATUS_NODE_OFFLINE = "NODE_OFFLINE";
    private static final String STATUS_CHECK_FAILED = "CHECK_FAILED";
    private static final String EXTERNAL_STATUS_UNKNOWN = "UNKNOWN";

    private static final int NODE_ONLINE = 1;
    private static final int MONITOR_DISABLED = 0;
    private static final int TCP_COUNT = 1;
    private static final int TCP_TIMEOUT_MS = 2000;
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 2;
    private static final int CHINA_FAILURE_COUNT_THRESHOLD = 4;
    private static final int CHINA_FAILURE_PERCENT_THRESHOLD = 60;
    private static final long MONITOR_PERSIST_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long MONITOR_COMMAND_TIMEOUT_MS = 3500L;

    private static final List<TcpTarget> GLOBAL_TARGETS = Arrays.asList(
            new TcpTarget("www.cloudflare.com", 443),
            new TcpTarget("one.one.one.one", 443),
            new TcpTarget("github.com", 443)
    );

    private static final List<TcpTarget> CHINA_TARGETS = Arrays.asList(
            new TcpTarget("www.baidu.com", 443),
            new TcpTarget("www.qq.com", 443),
            new TcpTarget("www.aliyun.com", 443),
            new TcpTarget("www.163.com", 443),
            new TcpTarget("sh-cm-v4.ip.zstaticcdn.com", 80),
            new TcpTarget("bj-cu-v4.ip.zstaticcdn.com", 80)
    );

    private final AtomicBoolean scheduledRunning = new AtomicBoolean(false);

    @Resource
    private NodeService nodeService;

    @Resource
    @Lazy
    private CloudflareDnsSyncService cloudflareDnsSyncService;

    @Resource(name = "wallMonitorExecutor")
    private Executor wallMonitorExecutor;

    @Override
    public void checkScheduledNodes() {
        if (!scheduledRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Node> nodes = nodeService.list(new QueryWrapper<Node>()
                    .select("id", "status", "wall_monitor_enabled", "wall_monitor_status",
                            "wall_monitor_last_check_at", "wall_monitor_consecutive_failures",
                            "wall_monitor_external_status"));
            if (nodes == null || nodes.isEmpty()) {
                return;
            }

            CompletableFuture<?>[] futures = nodes.stream()
                    .filter(this::isMonitorEnabled)
                    .map(node -> CompletableFuture.runAsync(() -> {
                        try {
                            checkAndStore(node);
                        } catch (Exception e) {
                            log.warn("Node wall monitor check failed, nodeId={}, error={}", node.getId(), e.getMessage());
                        }
                    }, wallMonitorExecutor))
                    .toArray(CompletableFuture[]::new);
            if (futures.length > 0) {
                CompletableFuture.allOf(futures).join();
            }
        } catch (Exception e) {
            log.warn("Node wall monitor scheduled check failed: {}", e.getMessage());
        } finally {
            scheduledRunning.set(false);
        }
    }

    @Override
    public R checkNodeNow(Long nodeId) {
        if (nodeId == null) {
            return R.err("节点ID不能为空");
        }
        Node node = nodeService.getById(nodeId);
        if (node == null) {
            return R.err("节点不存在");
        }
        if (!isMonitorEnabled(node)) {
            return R.err("节点被墙监测未开启");
        }

        if (hasExternalProbeResult(node)) {
            node.setSecret(null);
            node.setRemoteChangeIpToken(null);
            return R.ok(node);
        }

        try {
            checkAndStore(nodeId);
        } catch (Exception e) {
            return R.err("检测失败：" + e.getMessage());
        }
        Node updated = nodeService.getById(nodeId);
        if (updated != null) {
            updated.setSecret(null);
            updated.setRemoteChangeIpToken(null);
        }
        return R.ok(updated);
    }

    @Override
    public R markNodeUnavailableByExternalProbe(Long nodeId, String message) {
        if (nodeId == null) {
            return R.err("节点不存在");
        }
        Node node = nodeService.getById(nodeId);
        if (node == null) {
            return R.err("节点不存在");
        }

        String previousStatus = normalizeStatus(node.getWallMonitorExternalStatus());
        long now = System.currentTimeMillis();
        int nextFailures = safeInt(node.getWallMonitorExternalConsecutiveFailures()) + 1;
        if (!shouldPersistExternalResult(node, STATUS_SUSPECTED_BLOCKED, now)) {
            return R.ok("节点不可达状态未变化");
        }
        Node update = new Node();
        update.setId(nodeId);
        update.setWallMonitorEnabled(1);
        update.setWallMonitorExternalStatus(STATUS_SUSPECTED_BLOCKED);
        update.setWallMonitorExternalLastCheckAt(now);
        update.setWallMonitorExternalConsecutiveFailures(nextFailures);
        String normalizedMessage = trimMessage(
                message == null || message.trim().isEmpty()
                        ? "独立 Android 探针确认节点端口不可达"
                        : message);
        applyExternalStatusAsPrimary(update, STATUS_SUSPECTED_BLOCKED, now, nextFailures, normalizedMessage);
        update.setWallMonitorExternalMessage(normalizedMessage);
        nodeService.updateById(update);
        broadcastMonitorUpdate(update);
        syncCloudflareDnsIfExternalStatusChanged(nodeId, previousStatus, STATUS_SUSPECTED_BLOCKED);
        return R.ok("节点已标记为不可达");
    }

    @Override
    public R markNodeAvailableByExternalProbe(Long nodeId, String message) {
        if (nodeId == null) {
            return R.err("节点不存在");
        }
        Node node = nodeService.getById(nodeId);
        if (node == null) {
            return R.err("节点不存在");
        }

        String previousStatus = normalizeStatus(node.getWallMonitorExternalStatus());
        long now = System.currentTimeMillis();
        if (!shouldPersistExternalResult(node, STATUS_OK, now)) {
            return R.ok("节点可达状态未变化");
        }
        Node update = new Node();
        update.setId(nodeId);
        update.setWallMonitorEnabled(1);
        update.setWallMonitorExternalStatus(STATUS_OK);
        update.setWallMonitorExternalLastCheckAt(now);
        update.setWallMonitorExternalConsecutiveFailures(0);
        String normalizedMessage = trimMessage(
                message == null || message.trim().isEmpty()
                        ? "独立 Android 探针确认节点端口可达"
                        : message);
        applyExternalStatusAsPrimary(update, STATUS_OK, now, 0, normalizedMessage);
        update.setWallMonitorExternalMessage(normalizedMessage);
        nodeService.updateById(update);
        broadcastMonitorUpdate(update);
        syncCloudflareDnsIfExternalStatusChanged(nodeId, previousStatus, STATUS_OK);
        return R.ok("节点已标记为可达");
    }

    private void checkAndStore(Long nodeId) {
        checkAndStore(nodeService.getById(nodeId));
    }

    private void checkAndStore(Node node) {
        if (node == null || !isMonitorEnabled(node)) {
            return;
        }
        if (hasExternalProbeResult(node)) {
            return;
        }
        String previousStatus = node.getWallMonitorStatus();

        MonitorDecision decision;
        if (node.getStatus() == null || node.getStatus() != NODE_ONLINE) {
            decision = offlineDecision();
        } else {
            decision = runConnectivityCheck(node);
        }

        Node update = new Node();
        update.setId(node.getId());
        update.setWallMonitorEnabled(1);
        update.setWallMonitorStatus(decision.status);
        update.setWallMonitorLastCheckAt(System.currentTimeMillis());
        update.setWallMonitorConsecutiveFailures(decision.consecutiveFailures);
        update.setWallMonitorChinaSuccessCount(decision.chinaSuccessCount);
        update.setWallMonitorChinaTotalCount(decision.chinaTotalCount);
        update.setWallMonitorGlobalSuccessCount(decision.globalSuccessCount);
        update.setWallMonitorGlobalTotalCount(decision.globalTotalCount);
        update.setWallMonitorLatencyMs(decision.latencyMs);
        update.setWallMonitorMessage(trimMessage(decision.message));

        long now = System.currentTimeMillis();
        if (!shouldPersistMonitorResult(node, update, now)) {
            return;
        }

        nodeService.updateById(update);
        broadcastMonitorUpdate(update);
        syncCloudflareDnsIfStatusChanged(node.getId(), previousStatus, decision.status);
    }

    private MonitorDecision runConnectivityCheck(Node node) {
        ProbeSummary global = probeTargets(node.getId(), GLOBAL_TARGETS, 1, Integer.MAX_VALUE);
        if (global.successCount <= 0) {
            return decision(
                    STATUS_CHECK_FAILED,
                    0,
                    0,
                    0,
                    global.successCount,
                    global.totalCount,
                    global.averageLatencyMs(),
                    "国际连通性异常，跳过被墙判断：" + global.firstErrorOrDefault()
            );
        }

        int requiredChinaFailures = Math.max(CHINA_FAILURE_COUNT_THRESHOLD,
                (int) Math.ceil(CHINA_TARGETS.size() * (CHINA_FAILURE_PERCENT_THRESHOLD / 100.0D)));
        int clearSuccessThreshold = Math.max(1, CHINA_TARGETS.size() - requiredChinaFailures + 1);
        ProbeSummary china = probeTargets(node.getId(), CHINA_TARGETS, clearSuccessThreshold, requiredChinaFailures);
        if (china.totalCount <= 0) {
            return decision(
                    STATUS_CHECK_FAILED,
                    0,
                    0,
                    0,
                    global.successCount,
                    global.totalCount,
                    global.averageLatencyMs(),
                    "国内探测点不可用，暂不判断"
            );
        }

        int chinaFailureCount = china.totalCount - china.successCount;
        int failPercent = chinaFailureCount * 100 / china.totalCount;
        boolean suspected = chinaFailureCount >= CHINA_FAILURE_COUNT_THRESHOLD
                || failPercent >= CHINA_FAILURE_PERCENT_THRESHOLD;
        double latency = china.successCount > 0 ? china.averageLatencyMs() : global.averageLatencyMs();

        if (!suspected) {
            return decision(
                    STATUS_OK,
                    0,
                    china.successCount,
                    china.totalCount,
                    global.successCount,
                    global.totalCount,
                    latency,
                    String.format("正常：国内TCP成功 %d/%d，国际TCP成功 %d/%d",
                            china.successCount, china.totalCount, global.successCount, global.totalCount)
            );
        }

        int consecutiveFailures = safeInt(node.getWallMonitorConsecutiveFailures()) + 1;
        boolean confirmed = consecutiveFailures >= CONSECUTIVE_FAILURE_THRESHOLD;
        String status = confirmed ? STATUS_SUSPECTED_BLOCKED : STATUS_OBSERVING;
        String prefix = confirmed ? "疑似被墙" : "国内连通异常，继续观察";
        return decision(
                status,
                consecutiveFailures,
                china.successCount,
                china.totalCount,
                global.successCount,
                global.totalCount,
                latency,
                String.format("%s：国内TCP成功 %d/%d，失败率 %d%%，连续 %d/%d 次",
                        prefix, china.successCount, china.totalCount, failPercent,
                        consecutiveFailures, CONSECUTIVE_FAILURE_THRESHOLD)
        );
    }

    private ProbeSummary probeTargets(Long nodeId, List<TcpTarget> targets, int stopAfterSuccessCount, int stopAfterFailureCount) {
        ProbeSummary summary = new ProbeSummary();
        for (TcpTarget target : targets) {
            summary.totalCount++;
            ProbeResult result = tcpPing(nodeId, target);
            if (result.success) {
                summary.successCount++;
                summary.totalLatencyMs += Math.max(0, result.latencyMs);
                if (stopAfterSuccessCount > 0 && summary.successCount >= stopAfterSuccessCount) {
                    break;
                }
            } else if (summary.firstError == null) {
                summary.firstError = target + " " + result.message;
                if (stopAfterFailureCount > 0 && summary.totalCount - summary.successCount >= stopAfterFailureCount) {
                    break;
                }
            } else if (stopAfterFailureCount > 0 && summary.totalCount - summary.successCount >= stopAfterFailureCount) {
                break;
            }
        }
        return summary;
    }

    private ProbeResult tcpPing(Long nodeId, TcpTarget target) {
        try {
            JSONObject tcpPingData = new JSONObject();
            tcpPingData.put("ip", target.host);
            tcpPingData.put("port", target.port);
            tcpPingData.put("count", TCP_COUNT);
            tcpPingData.put("timeout", TCP_TIMEOUT_MS);

            GostDto gostResult = WebSocketServer.send_msg(
                    nodeId, tcpPingData, "TcpPing", MONITOR_COMMAND_TIMEOUT_MS);
            if (gostResult == null) {
                return ProbeResult.fail("节点无响应");
            }
            if (!"OK".equals(gostResult.getMsg())) {
                return ProbeResult.fail(gostResult.getMsg());
            }
            if (gostResult.getData() == null) {
                return ProbeResult.ok(0);
            }

            JSONObject response = toJsonObject(gostResult.getData());
            boolean success = response.getBooleanValue("success");
            if (success) {
                return ProbeResult.ok(response.getDoubleValue("averageTime"));
            }
            String errorMessage = response.getString("errorMessage");
            return ProbeResult.fail(errorMessage == null ? "TCP连接失败" : errorMessage);
        } catch (Exception e) {
            return ProbeResult.fail(e.getMessage());
        }
    }

    private JSONObject toJsonObject(Object data) {
        if (data instanceof JSONObject) {
            return (JSONObject) data;
        }
        return JSON.parseObject(JSON.toJSONString(data));
    }

    private MonitorDecision offlineDecision() {
        return decision(
                STATUS_NODE_OFFLINE,
                0,
                0,
                0,
                0,
                0,
                0,
                "节点离线，跳过检测"
        );
    }

    private MonitorDecision decision(String status,
                                     int consecutiveFailures,
                                     int chinaSuccessCount,
                                     int chinaTotalCount,
                                     int globalSuccessCount,
                                     int globalTotalCount,
                                     double latencyMs,
                                     String message) {
        MonitorDecision decision = new MonitorDecision();
        decision.status = status == null ? STATUS_PENDING : status;
        decision.consecutiveFailures = Math.max(0, consecutiveFailures);
        decision.chinaSuccessCount = Math.max(0, chinaSuccessCount);
        decision.chinaTotalCount = Math.max(0, chinaTotalCount);
        decision.globalSuccessCount = Math.max(0, globalSuccessCount);
        decision.globalTotalCount = Math.max(0, globalTotalCount);
        decision.latencyMs = Math.round(Math.max(0, latencyMs) * 100.0) / 100.0;
        decision.message = message == null ? "" : message;
        return decision;
    }

    private boolean isMonitorEnabled(Node node) {
        return node != null && (node.getWallMonitorEnabled() == null || node.getWallMonitorEnabled() != MONITOR_DISABLED);
    }

    private boolean hasExternalProbeResult(Node node) {
        if (node == null) {
            return false;
        }
        String status = normalizeStatus(node.getWallMonitorExternalStatus());
        return STATUS_OK.equals(status) || STATUS_SUSPECTED_BLOCKED.equals(status);
    }

    private void applyExternalStatusAsPrimary(Node update,
                                               String status,
                                               long checkedAt,
                                               int consecutiveFailures,
                                               String message) {
        update.setWallMonitorStatus(status);
        update.setWallMonitorLastCheckAt(checkedAt);
        update.setWallMonitorConsecutiveFailures(Math.max(0, consecutiveFailures));
        update.setWallMonitorMessage(message);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimMessage(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void broadcastMonitorUpdate(Node node) {
        try {
            JSONObject data = new JSONObject();
            data.put("wallMonitorEnabled", node.getWallMonitorEnabled());
            data.put("wallMonitorStatus", node.getWallMonitorStatus());
            data.put("wallMonitorLastCheckAt", node.getWallMonitorLastCheckAt());
            data.put("wallMonitorConsecutiveFailures", node.getWallMonitorConsecutiveFailures());
            data.put("wallMonitorChinaSuccessCount", node.getWallMonitorChinaSuccessCount());
            data.put("wallMonitorChinaTotalCount", node.getWallMonitorChinaTotalCount());
            data.put("wallMonitorGlobalSuccessCount", node.getWallMonitorGlobalSuccessCount());
            data.put("wallMonitorGlobalTotalCount", node.getWallMonitorGlobalTotalCount());
            data.put("wallMonitorLatencyMs", node.getWallMonitorLatencyMs());
            data.put("wallMonitorMessage", node.getWallMonitorMessage());
            data.put("wallMonitorExternalStatus", node.getWallMonitorExternalStatus());
            data.put("wallMonitorExternalLastCheckAt", node.getWallMonitorExternalLastCheckAt());
            data.put("wallMonitorExternalConsecutiveFailures", node.getWallMonitorExternalConsecutiveFailures());
            data.put("wallMonitorExternalMessage", node.getWallMonitorExternalMessage());

            JSONObject message = new JSONObject();
            message.put("id", node.getId());
            message.put("type", "wallMonitor");
            message.put("data", data);
            WebSocketServer.broadcastMessage(message.toJSONString());
        } catch (Exception e) {
            log.warn("Broadcast node wall monitor update failed, nodeId={}, error={}", node.getId(), e.getMessage());
        }
    }

    private void syncCloudflareDnsIfStatusChanged(Long nodeId, String previousStatus, String nextStatus) {
        if (Objects.equals(normalizeStatus(previousStatus), normalizeStatus(nextStatus))) {
            return;
        }
        cloudflareDnsSyncService.requestSyncAll("wall-monitor");
    }

    private void syncCloudflareDnsIfExternalStatusChanged(Long nodeId, String previousStatus, String nextStatus) {
        if (Objects.equals(normalizeStatus(previousStatus), normalizeStatus(nextStatus))) {
            return;
        }
        cloudflareDnsSyncService.requestSyncAll("external-probe");
    }

    private boolean shouldPersistMonitorResult(Node previous, Node update, long now) {
        if (!Objects.equals(normalizeStatus(previous.getWallMonitorStatus()),
                normalizeStatus(update.getWallMonitorStatus()))) {
            return true;
        }
        if (!Objects.equals(previous.getWallMonitorConsecutiveFailures(), update.getWallMonitorConsecutiveFailures())) {
            return true;
        }
        Long lastCheckAt = previous.getWallMonitorLastCheckAt();
        return lastCheckAt == null || now - lastCheckAt >= MONITOR_PERSIST_INTERVAL_MS;
    }

    private boolean shouldPersistExternalResult(Node previous, String nextStatus, long now) {
        if (!Objects.equals(normalizeStatus(previous.getWallMonitorExternalStatus()), nextStatus)) {
            return true;
        }
        Long lastCheckAt = previous.getWallMonitorExternalLastCheckAt();
        return lastCheckAt == null || now - lastCheckAt >= MONITOR_PERSIST_INTERVAL_MS;
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }

    private static class TcpTarget {
        private final String host;
        private final int port;

        private TcpTarget(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private static class ProbeResult {
        private boolean success;
        private double latencyMs;
        private String message;

        private static ProbeResult ok(double latencyMs) {
            ProbeResult result = new ProbeResult();
            result.success = true;
            result.latencyMs = latencyMs;
            result.message = "OK";
            return result;
        }

        private static ProbeResult fail(String message) {
            ProbeResult result = new ProbeResult();
            result.success = false;
            result.latencyMs = 0;
            result.message = message == null ? "TCP连接失败" : message;
            return result;
        }
    }

    private static class ProbeSummary {
        private int successCount;
        private int totalCount;
        private double totalLatencyMs;
        private String firstError;

        private double averageLatencyMs() {
            return successCount <= 0 ? 0 : totalLatencyMs / successCount;
        }

        private String firstErrorOrDefault() {
            return firstError == null ? "无可用探测点" : firstError;
        }
    }

    private static class MonitorDecision {
        private String status;
        private int consecutiveFailures;
        private int chinaSuccessCount;
        private int chinaTotalCount;
        private int globalSuccessCount;
        private int globalTotalCount;
        private double latencyMs;
        private String message;
    }
}
