package com.admin.common.task;

import com.admin.service.CloudflareDnsSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class CloudflareDnsSyncTask {

    @Resource
    private CloudflareDnsSyncService cloudflareDnsSyncService;

    @Scheduled(fixedDelay = 60000)
    public void syncDueBindings() {
        try {
            cloudflareDnsSyncService.syncDueBindings();
        } catch (Exception e) {
            log.warn("Cloudflare DNS scheduled sync failed: {}", e.getMessage());
        }
    }
}
