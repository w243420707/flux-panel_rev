package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** Host reboot control, with a persisted, one-shot claim for each scheduled occurrence. */
@Slf4j
@Service
public class NodeRebootService {
    private static final long REBOOT_WAIT_MS = TimeUnit.MINUTES.toMillis(2);
    private final NodeMapper nodeMapper;
    private final Executor executor;
    private final Clock clock;
    private final IntSupplier randomSeconds;
    private final Object[] locks = new Object[64];
    private final ConcurrentHashMap<Long, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> queued = new ConcurrentHashMap<>();
    private final Set<Long> connectedSinceStartup = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;
    private volatile boolean stopping;

    @Autowired
    public NodeRebootService(NodeMapper nodeMapper, @Qualifier("deferredForwardExecutor") Executor executor) {
        this(nodeMapper, executor, Clock.systemUTC(), () -> ThreadLocalRandom.current().nextInt(3600));
    }

    NodeRebootService(NodeMapper nodeMapper, Executor executor, Clock clock, IntSupplier randomSeconds) {
        this.nodeMapper = nodeMapper;
        this.executor = executor;
        this.clock = clock;
        this.randomSeconds = randomSeconds;
        Arrays.setAll(locks, i -> new Object());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ready = true; // Migrations run before the first scheduler query.
    }

    @EventListener(ContextClosedEvent.class)
    public void onStopping() {
        stopping = true;
        ready = false;
    }

    public boolean isStopping() {
        return stopping;
    }

    public R saveSchedule(Long id, int hours) {
        if (hours < 0 || hours > 720) return R.err("重启间隔必须为0到720之间的整数小时");
        synchronized (lock(id)) {
            Node node = nodeMapper.selectById(id);
            if (node == null) return R.err("节点不存在");
            if (hours > 0 && !supportsReboot(node.getVersion())) return R.err("请先将节点更新到3.1.6或更高版本");
            Long nextAt = hours > 0 && isOnline(id) ? nextTime(hours) : null;
            nodeMapper.updateRebootSchedule(id, hours, nextAt);
            node.setRebootIntervalHours(hours);
            node.setRebootNextAt(nextAt);
            publish(node, "rebootSchedule", null, null);
            return R.ok(scheduleData(node));
        }
    }

    public R reboot(Long id) {
        return dispatch(id, null);
    }

    private R dispatch(Long id, Long expectedAt) {
        Attempt attempt;
        synchronized (lock(id)) {
            if (stopping) return R.err("面板正在关闭，未发送重启指令");
            Node node = nodeMapper.selectById(id);
            if (node == null) return R.err("节点不存在");
            if (expectedAt != null && (interval(node) == 0 || !expectedAt.equals(node.getRebootNextAt()))) {
                return R.err("定时重启计划已变更");
            }
            if (!isOnline(id) || !supportsReboot(node.getVersion())) {
                if (expectedAt != null) clearScheduleTime(node);
                return R.err(!isOnline(id) ? "节点离线，无法发送重启指令" : "请先将节点更新到3.1.6或更高版本");
            }
            Attempt previous = attempts.get(id);
            if (previous != null && previous.failure == null && previous.expiresAt > clock.millis()) {
                return R.err("重启指令已发送，请等待节点重新上线");
            }
            if (expectedAt != null) {
                if (nodeMapper.claimDueReboot(id, expectedAt, clock.millis()) != 1) return R.err("本轮计划已处理");
            } else {
                nodeMapper.updateRebootNextAt(id, null);
            }
            node.setRebootNextAt(null);
            attempt = new Attempt(UUID.randomUUID().toString(), clock.millis() + REBOOT_WAIT_MS,
                    WebSocketServer.getNodeConnectionId(id));
            attempts.put(id, attempt);
            publish(node, "rebootSchedule", null, null);
        }

        // Never hold the node lock while waiting: the WebSocket thread must be free to deliver ACKs/status.
        GostDto response;
        try {
            response = sendReboot(id, attempt.requestId);
        } catch (Exception e) {
            log.warn("Node reboot transport failed, nodeId={}: {}", id, e.getMessage());
            response = null;
        }
        synchronized (lock(id)) {
            Node node = nodeMapper.selectById(id);
            if (attempt.failure != null) return R.err(attempt.failure);
            boolean accepted = response != null && Integer.valueOf(0).equals(response.getCode())
                    && "OK".equals(response.getMsg());
            if (accepted) {
                // A newer save/reconnection wins over an older ACK. Only restore a still-empty active plan.
                if (node != null && attempts.get(id) == attempt && isOnline(id)
                        && interval(node) > 0 && node.getRebootNextAt() == null) {
                    node.setRebootNextAt(nextTime(interval(node)));
                    nodeMapper.updateRebootNextAt(id, node.getRebootNextAt());
                }
                log.info("Node reboot accepted, nodeId={}, source={}, requestId={}", id,
                        expectedAt == null ? "manual" : "schedule", attempt.requestId);
                if (node != null && attempts.get(id) == attempt) {
                    publish(node, "reboot", "accepted", "已发送重启指令，等待节点重新上线");
                }
                return R.ok(node == null ? null : scheduleData(node));
            }
            boolean rejected = response != null && Integer.valueOf(-1).equals(response.getCode());
            String message = rejected ? response.getMsg() : "未收到重启回执，结果未知，请等待节点状态更新";
            if (rejected) attempts.remove(id, attempt);
            if (node != null) publish(node, "reboot", rejected ? "failed" : "unknown", message);
            // Failed/uncertain schedules stay NULL until a reconnect or an explicit save; no blind retries.
            return R.err(rejected ? -1 : -2, message);
        }
    }

    /** Called only for the current authenticated node session. */
    public void onExecutionFailure(Long id, String requestId, String message) {
        synchronized (lock(id)) {
            Attempt attempt = attempts.get(id);
            if (attempt == null || !attempt.requestId.equals(requestId)) return;
            attempt.failure = message == null || message.isBlank() ? "节点执行重启失败" : message;
            Node node = nodeMapper.selectById(id);
            if (node != null) {
                clearScheduleTime(node);
                publish(node, "reboot", "failed", attempt.failure);
            }
            log.warn("Node reboot execution failed, nodeId={}, requestId={}: {}", id, requestId, attempt.failure);
        }
    }

    public void onOnline(Long id, boolean previouslyOnline) {
        synchronized (lock(id)) {
            if (stopping) return;
            Node node = nodeMapper.selectById(id);
            if (node == null) return;
            boolean firstConnection = connectedSinceStartup.add(id);
            boolean wasRebooting = attempts.remove(id) != null;
            Long next = node.getRebootNextAt();
            if (interval(node) > 0 && supportsReboot(node.getVersion())) {
                // Keep a future persisted deadline across panel restarts; never catch up overdue reboots.
                if (wasRebooting || !firstConnection || !previouslyOnline || next == null || next <= clock.millis()) {
                    next = nextTime(interval(node));
                }
            } else {
                next = null;
            }
            nodeMapper.updateRebootNextAt(id, next);
            node.setRebootNextAt(next);
            publish(node, "rebootSchedule", null, null);
        }
    }

    public void onOffline(Long id) {
        synchronized (lock(id)) {
            if (stopping) return;
            Node node = nodeMapper.selectById(id);
            if (node != null) clearScheduleTime(node);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void enqueueDueReboots() {
        if (!ready) return;
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt <= clock.millis());
        try {
            for (Node node : nodeMapper.findDueReboots(clock.millis())) {
                if (queued.putIfAbsent(node.getId(), true) != null) continue;
                try {
                    executor.execute(() -> {
                        try {
                            dispatch(node.getId(), node.getRebootNextAt());
                        } catch (Exception e) {
                            log.warn("Scheduled node reboot failed, nodeId={}: {}", node.getId(), e.getMessage());
                        } finally {
                            queued.remove(node.getId());
                        }
                    });
                } catch (RuntimeException e) {
                    queued.remove(node.getId());
                    throw e;
                }
            }
        } catch (Exception e) {
            log.warn("Cannot scan node reboot schedules: {}", e.getMessage());
        }
    }

    private void clearScheduleTime(Node node) {
        nodeMapper.updateRebootNextAt(node.getId(), null);
        node.setRebootNextAt(null);
        publish(node, "rebootSchedule", null, null);
    }

    private Object lock(Long id) {
        return locks[Math.floorMod(id.hashCode(), locks.length)];
    }

    private int interval(Node node) {
        return node.getRebootIntervalHours() == null ? 0 : node.getRebootIntervalHours();
    }

    private long nextTime(int hours) {
        return clock.millis() + TimeUnit.HOURS.toMillis(hours) + TimeUnit.SECONDS.toMillis(randomSeconds.getAsInt());
    }

    public static boolean supportsReboot(String version) {
        if (version == null || !version.matches("v?\\d+\\.\\d+\\.\\d+")) return false;
        try {
            String[] parts = version.replaceFirst("^v", "").split("\\.");
            int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]), patch = Integer.parseInt(parts[2]);
            return major > 3 || major == 3 && (minor > 1 || minor == 1 && patch >= 6);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private JSONObject scheduleData(Node node) {
        JSONObject data = new JSONObject();
        data.put("rebootIntervalHours", interval(node));
        data.put("rebootNextAt", node.getRebootNextAt());
        return data;
    }

    boolean isOnline(Long id) {
        return WebSocketServer.isNodeConnected(id);
    }

    GostDto sendReboot(Long id, String requestId) {
        Attempt attempt = attempts.get(id);
        if (attempt == null || !attempt.requestId.equals(requestId) || attempt.connectionId == null) {
            GostDto result = new GostDto();
            result.setCode(-1);
            result.setMsg("节点连接已变更，未发送重启指令");
            return result;
        }
        return WebSocketServer.send_msg(id, Collections.emptyMap(), "RebootNode", 5000L, requestId,
                attempt.connectionId, () -> !stopping && attempts.get(id) == attempt);
    }

    void publish(Node node, String type, String status, String message) {
        JSONObject data = scheduleData(node);
        if (status != null) data.put("status", status);
        if (message != null) data.put("message", message);
        JSONObject event = new JSONObject();
        event.put("id", node.getId());
        event.put("type", type);
        event.put("data", data);
        // Fastjson drops null map values by default; explicitly send NULL to clear stale deadlines in browsers.
        WebSocketServer.broadcastMessage(JSONObject.toJSONString(event,
                com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue));
    }

    private static class Attempt {
        final String requestId;
        final long expiresAt;
        final String connectionId;
        String failure;

        Attempt(String requestId, long expiresAt, String connectionId) {
            this.requestId = requestId;
            this.expiresAt = expiresAt;
            this.connectionId = connectionId;
        }
    }
}
