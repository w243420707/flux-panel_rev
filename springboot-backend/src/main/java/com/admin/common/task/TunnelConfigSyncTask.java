package com.admin.common.task;

import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.entity.Tunnel;
import com.admin.service.CloudflareDnsSyncService;
import com.admin.service.ForwardService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TunnelConfigSyncTask {

    private static final ConcurrentHashMap<Long, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    @Lazy
    private CloudflareDnsSyncService cloudflareDnsSyncService;

    @Async("tunnelSyncExecutor")
    public void syncTunnelUpdate(Long tunnelId, Tunnel oldTunnel, boolean configChanged, boolean dnsSync, String trigger) {
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
                            }
                        } catch (Exception e) {
                            failed++;
                            log.warn("Tunnel {} forward {} config sync exception", tunnelId, forward.getId(), e);
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
}
