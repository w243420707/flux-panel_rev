package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.service.CloudflareDnsSyncService;
import com.admin.service.ForwardService;
import com.admin.service.TunnelService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TunnelConfigSyncTask {

    private static final ConcurrentHashMap<Long, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ForwardRetryJob> FORWARD_RETRY_QUEUE = new ConcurrentHashMap<>();

    private static final int MAX_FORWARD_RETRY_ATTEMPTS = 5;
    private static final int MAX_RETRY_SUBMISSIONS_PER_TICK = 50;
    private static final long RETRY_BASE_DELAY_MILLIS = 15000L;
    private static final long RETRY_MAX_DELAY_MILLIS = 300000L;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    @Lazy
    private CloudflareDnsSyncService cloudflareDnsSyncService;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource(name = "tunnelSyncExecutor")
    private Executor tunnelSyncExecutor;

    @Async("tunnelSyncExecutor")
    public void syncTunnelUpdate(Long tunnelId, Tunnel oldTunnel, Long syncVersion, boolean configChanged, boolean dnsSync, String trigger) {
        if (tunnelId == null) {
            return;
        }

        Object lock = TUNNEL_LOCKS.computeIfAbsent(tunnelId, ignored -> new Object());
        synchronized (lock) {
            int total = 0;
            int failed = 0;

            if (configChanged) {
                List<Forward> forwards = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId));
                total = forwards == null ? 0 : forwards.size();
                if (forwards != null) {
                    for (Forward forward : forwards) {
                        try {
                            R result = forwardService.refreshForwardConfig(forward, oldTunnel);
                            if (result.getCode() != 0) {
                                failed++;
                                log.warn("Tunnel {} forward {} config sync failed: {}", tunnelId, forward.getId(), result.getMsg());
                                queueForwardRetry(tunnelId, forward.getId(), oldTunnel, syncVersion, 1, result.getMsg());
                            }
                        } catch (Exception e) {
                            failed++;
                            log.warn("Tunnel {} forward {} config sync exception", tunnelId, forward.getId(), e);
                            queueForwardRetry(tunnelId, forward.getId(), oldTunnel, syncVersion, 1, e.getMessage());
                        }
                    }
                }
            }

            if (dnsSync) {
                try {
                    R result = cloudflareDnsSyncService.syncBindingsByTunnel(tunnelId, trigger);
                    if (result.getCode() != 0) {
                        log.warn("Tunnel {} Cloudflare DNS sync failed: {}", tunnelId, result.getMsg());
                    }
                } catch (Exception e) {
                    log.warn("Tunnel {} Cloudflare DNS sync exception", tunnelId, e);
                }
            }

            log.info("Tunnel {} background sync finished, forwards={}, failed={}", tunnelId, total, failed);
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void processForwardRetryQueue() {
        if (FORWARD_RETRY_QUEUE.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<ForwardRetryJob> dueJobs = new ArrayList<>();
        for (ForwardRetryJob job : FORWARD_RETRY_QUEUE.values()) {
            if (job.getNextRetryAtMillis() <= now) {
                dueJobs.add(job);
                if (dueJobs.size() >= MAX_RETRY_SUBMISSIONS_PER_TICK) {
                    break;
                }
            }
        }

        for (ForwardRetryJob job : dueJobs) {
            if (FORWARD_RETRY_QUEUE.remove(job.getKey(), job)) {
                tunnelSyncExecutor.execute(() -> runForwardRetry(job));
            }
        }
    }

    private void runForwardRetry(ForwardRetryJob job) {
        Object lock = TUNNEL_LOCKS.computeIfAbsent(job.getTunnelId(), ignored -> new Object());
        synchronized (lock) {
            Tunnel currentTunnel = tunnelService.getById(job.getTunnelId());
            if (currentTunnel == null) {
                log.info("Skip stale forward retry, tunnel {} no longer exists, forward={}", job.getTunnelId(), job.getForwardId());
                return;
            }
            if (!Objects.equals(currentTunnel.getUpdatedTime(), job.getSyncVersion())) {
                log.info("Skip stale forward retry, tunnel {} version changed from {} to {}, forward={}",
                        job.getTunnelId(), job.getSyncVersion(), currentTunnel.getUpdatedTime(), job.getForwardId());
                return;
            }

            Forward forward = forwardService.getById(job.getForwardId());
            if (forward == null || !Objects.equals(forward.getTunnelId(), job.getTunnelId().intValue())) {
                log.info("Skip stale forward retry, forward {} no longer belongs to tunnel {}", job.getForwardId(), job.getTunnelId());
                return;
            }

            try {
                R result = forwardService.refreshForwardConfig(forward, job.getOldTunnel());
                if (result.getCode() == 0) {
                    log.info("Tunnel {} forward {} config retry succeeded, attempt={}", job.getTunnelId(), job.getForwardId(), job.getAttempt());
                    return;
                }
                retryOrDrop(job, result.getMsg());
            } catch (Exception e) {
                log.warn("Tunnel {} forward {} config retry exception, attempt={}", job.getTunnelId(), job.getForwardId(), job.getAttempt(), e);
                retryOrDrop(job, e.getMessage());
            }
        }
    }

    private void retryOrDrop(ForwardRetryJob job, String reason) {
        if (job.getAttempt() >= MAX_FORWARD_RETRY_ATTEMPTS) {
            log.warn("Tunnel {} forward {} config retry reached max attempts, last error={}", job.getTunnelId(), job.getForwardId(), reason);
            return;
        }
        queueForwardRetry(job.getTunnelId(), job.getForwardId(), job.getOldTunnel(), job.getSyncVersion(), job.getAttempt() + 1, reason);
    }

    private void queueForwardRetry(Long tunnelId, Long forwardId, Tunnel oldTunnel, Long syncVersion, int attempt, String reason) {
        if (tunnelId == null || forwardId == null || syncVersion == null || attempt > MAX_FORWARD_RETRY_ATTEMPTS) {
            return;
        }

        ForwardRetryJob job = new ForwardRetryJob();
        job.setTunnelId(tunnelId);
        job.setForwardId(forwardId);
        job.setSyncVersion(syncVersion);
        job.setOldTunnel(copyTunnel(oldTunnel));
        job.setAttempt(attempt);
        job.setNextRetryAtMillis(System.currentTimeMillis() + retryDelayMillis(attempt));
        job.setLastError(reason);
        job.setKey(retryKey(tunnelId, forwardId, syncVersion));

        FORWARD_RETRY_QUEUE.put(job.getKey(), job);
        log.info("Queued tunnel {} forward {} config retry, attempt={}, retryAt={}, reason={}",
                tunnelId, forwardId, attempt, job.getNextRetryAtMillis(), reason);
    }

    private long retryDelayMillis(int attempt) {
        long multiplier = 1L << Math.max(0, Math.min(attempt - 1, 4));
        return Math.min(RETRY_BASE_DELAY_MILLIS * multiplier, RETRY_MAX_DELAY_MILLIS);
    }

    private String retryKey(Long tunnelId, Long forwardId, Long syncVersion) {
        return tunnelId + ":" + forwardId + ":" + syncVersion;
    }

    private Tunnel copyTunnel(Tunnel oldTunnel) {
        if (oldTunnel == null) {
            return null;
        }
        Tunnel copy = new Tunnel();
        BeanUtils.copyProperties(oldTunnel, copy);
        return copy;
    }

    private static class ForwardRetryJob {
        private String key;
        private Long tunnelId;
        private Long forwardId;
        private Long syncVersion;
        private Tunnel oldTunnel;
        private int attempt;
        private long nextRetryAtMillis;
        private String lastError;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Long getTunnelId() {
            return tunnelId;
        }

        public void setTunnelId(Long tunnelId) {
            this.tunnelId = tunnelId;
        }

        public Long getForwardId() {
            return forwardId;
        }

        public void setForwardId(Long forwardId) {
            this.forwardId = forwardId;
        }

        public Long getSyncVersion() {
            return syncVersion;
        }

        public void setSyncVersion(Long syncVersion) {
            this.syncVersion = syncVersion;
        }

        public Tunnel getOldTunnel() {
            return oldTunnel;
        }

        public void setOldTunnel(Tunnel oldTunnel) {
            this.oldTunnel = oldTunnel;
        }

        public int getAttempt() {
            return attempt;
        }

        public void setAttempt(int attempt) {
            this.attempt = attempt;
        }

        public long getNextRetryAtMillis() {
            return nextRetryAtMillis;
        }

        public void setNextRetryAtMillis(long nextRetryAtMillis) {
            this.nextRetryAtMillis = nextRetryAtMillis;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(String lastError) {
            this.lastError = lastError;
        }
    }
}
