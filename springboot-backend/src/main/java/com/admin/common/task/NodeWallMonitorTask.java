package com.admin.common.task;

import com.admin.service.NodeWallMonitorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class NodeWallMonitorTask {

    @Resource
    private NodeWallMonitorService nodeWallMonitorService;

    @Scheduled(initialDelay = 30000, fixedDelayString = "${node.wall-monitor.interval-ms:120000}")
    public void checkNodes() {
        try {
            nodeWallMonitorService.checkScheduledNodes();
        } catch (Exception e) {
            log.warn("Node wall monitor task failed: {}", e.getMessage());
        }
    }
}
