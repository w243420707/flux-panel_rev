package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.service.CloudflareDnsSyncService;
import com.admin.service.NodeService;
import com.admin.service.NodeWallMonitorService;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Objects;

@Slf4j
@Service
public class NodeWallMonitorServiceImpl implements NodeWallMonitorService {

    private static final String STATUS_OK = "OK";
    private static final String STATUS_SUSPECTED_BLOCKED = "SUSPECTED_BLOCKED";
    private static final long EXTERNAL_RESULT_PERSIST_INTERVAL_MS = 5 * 60 * 1000L;

    @Resource
    private NodeService nodeService;

    @Resource
    @Lazy
    private CloudflareDnsSyncService cloudflareDnsSyncService;

    @Override
    public R markNodeUnavailableByExternalProbe(Long nodeId, String message) {
        if (nodeId == null) {
            return R.err("node id is required");
        }
        Node node = nodeService.getById(nodeId);
        if (node == null) {
            return R.err("node not found");
        }

        String previousStatus = normalizeStatus(node.getWallMonitorExternalStatus());
        long now = System.currentTimeMillis();
        int nextFailures = safeInt(node.getWallMonitorExternalConsecutiveFailures()) + 1;
        if (!shouldPersistExternalResult(node, STATUS_SUSPECTED_BLOCKED, now)) {
            return R.ok("external probe status unchanged");
        }

        Node update = new Node();
        update.setId(nodeId);
        update.setWallMonitorEnabled(1);
        update.setWallMonitorExternalStatus(STATUS_SUSPECTED_BLOCKED);
        update.setWallMonitorExternalLastCheckAt(now);
        update.setWallMonitorExternalConsecutiveFailures(nextFailures);
        String normalizedMessage = trimMessage(message, "Android probe marked the node unreachable");
        applyExternalStatusAsPrimary(update, STATUS_SUSPECTED_BLOCKED, now, nextFailures, normalizedMessage);
        update.setWallMonitorExternalMessage(normalizedMessage);
        nodeService.updateById(update);
        broadcastMonitorUpdate(update);
        syncCloudflareDnsIfStatusChanged(previousStatus, STATUS_SUSPECTED_BLOCKED, "android-probe");
        return R.ok("node marked as unreachable");
    }

    @Override
    public R markNodeAvailableByExternalProbe(Long nodeId, String message) {
        if (nodeId == null) {
            return R.err("node id is required");
        }
        Node node = nodeService.getById(nodeId);
        if (node == null) {
            return R.err("node not found");
        }

        String previousStatus = normalizeStatus(node.getWallMonitorExternalStatus());
        long now = System.currentTimeMillis();
        if (!shouldPersistExternalResult(node, STATUS_OK, now)) {
            return R.ok("external probe status unchanged");
        }

        Node update = new Node();
        update.setId(nodeId);
        update.setWallMonitorEnabled(1);
        update.setWallMonitorExternalStatus(STATUS_OK);
        update.setWallMonitorExternalLastCheckAt(now);
        update.setWallMonitorExternalConsecutiveFailures(0);
        String normalizedMessage = trimMessage(message, "Android probe marked the node reachable");
        applyExternalStatusAsPrimary(update, STATUS_OK, now, 0, normalizedMessage);
        update.setWallMonitorExternalMessage(normalizedMessage);
        nodeService.updateById(update);
        broadcastMonitorUpdate(update);
        syncCloudflareDnsIfStatusChanged(previousStatus, STATUS_OK, "android-probe");
        return R.ok("node marked as reachable");
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

    private void broadcastMonitorUpdate(Node node) {
        try {
            JSONObject data = new JSONObject();
            data.put("wallMonitorEnabled", node.getWallMonitorEnabled());
            data.put("wallMonitorStatus", node.getWallMonitorStatus());
            data.put("wallMonitorLastCheckAt", node.getWallMonitorLastCheckAt());
            data.put("wallMonitorConsecutiveFailures", node.getWallMonitorConsecutiveFailures());
            data.put("wallMonitorMessage", node.getWallMonitorMessage());
            data.put("wallMonitorExternalStatus", node.getWallMonitorExternalStatus());
            data.put("wallMonitorExternalLastCheckAt", node.getWallMonitorExternalLastCheckAt());
            data.put("wallMonitorExternalConsecutiveFailures", node.getWallMonitorExternalConsecutiveFailures());
            data.put("wallMonitorExternalMessage", node.getWallMonitorExternalMessage());

            JSONObject event = new JSONObject();
            event.put("id", node.getId());
            event.put("type", "wallMonitor");
            event.put("data", data);
            WebSocketServer.broadcastMessage(event.toJSONString());
        } catch (Exception e) {
            log.warn("Failed to broadcast Android probe update, nodeId={}, error={}", node.getId(), e.getMessage());
        }
    }

    private void syncCloudflareDnsIfStatusChanged(String previousStatus, String nextStatus, String trigger) {
        if (Objects.equals(normalizeStatus(previousStatus), normalizeStatus(nextStatus))) {
            return;
        }
        cloudflareDnsSyncService.requestSyncAll(trigger);
    }

    private boolean shouldPersistExternalResult(Node previous, String nextStatus, long now) {
        if (!Objects.equals(normalizeStatus(previous.getWallMonitorExternalStatus()), nextStatus)) {
            return true;
        }
        Long lastCheckAt = previous.getWallMonitorExternalLastCheckAt();
        return lastCheckAt == null || now - lastCheckAt >= EXTERNAL_RESULT_PERSIST_INTERVAL_MS;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimMessage(String value, String fallback) {
        String normalized = value == null || value.trim().isEmpty() ? fallback : value.trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }
}
