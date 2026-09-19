package com.admin.controller;


import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.dto.NodeRebootDto;
import com.admin.common.dto.NodeRebootScheduleDto;
import com.admin.common.dto.NodeUsageConfirmDto;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.service.NodeRebootService;
import com.admin.service.NodeUsageService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/node")
public class NodeController extends BaseController {

    @Resource
    private NodeRebootService nodeRebootService;

    @Resource
    private NodeUsageService nodeUsageService;

    @Resource(name = "myHandler")
    private WebSocketServer webSocketServer;

    @LogAnnotation
    @RequireRole
    @PostMapping("/usage-history")
    public R usageHistory(@Validated @RequestBody NodeRebootDto request) {
        return nodeUsageService.history(request.getId());
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/usage-confirm")
    public R confirmUsage(@Validated @RequestBody NodeUsageConfirmDto request) {
        synchronized (WebSocketServer.nodeLifecycleLock(request.getId())) {
            if (!WebSocketServer.hasUsageCandidateConnection(request.getId())) {
                return R.err("待确认的节点已断开，请刷新后重试");
            }
            try {
                NodeUsageService.ConfirmationResult result = nodeUsageService.confirm(request);
                webSocketServer.activateUsageCandidate(request.getId(), result.acceptedSessionId(), result.usageId());
                WebSocketServer.broadcastUsage(request.getId(), result.summary());
                return R.ok(result.summary());
            } catch (IllegalArgumentException e) {
                return R.err(e.getMessage());
            }
        }
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/reboot")
    public R reboot(@Validated @RequestBody NodeRebootDto request) {
        return nodeRebootService.reboot(request.getId());
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/reboot-schedule")
    public R rebootSchedule(@Validated @RequestBody NodeRebootScheduleDto request) {
        return nodeRebootService.saveSchedule(request.getId(), request.getIntervalHours().intValueExact());
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/create")
    public R create(@Validated @RequestBody NodeDto nodeDto) {
        return nodeService.createNode(nodeDto);
    }


    @LogAnnotation
    @RequireRole
    @PostMapping("/list")
    public R list() {
        return nodeService.getAllNodes();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/update")
    public R update(@Validated @RequestBody NodeUpdateDto nodeUpdateDto) {
        return nodeService.updateNode(nodeUpdateDto);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return nodeService.deleteNode(id);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/install")
    public R getInstallCommand(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        String publicBaseUrl = params.get("publicBaseUrl") == null
                ? null
                : params.get("publicBaseUrl").toString();
        String assetMode = params.get("assetMode") == null
                ? null
                : params.get("assetMode").toString();
        return nodeService.getInstallCommand(id, publicBaseUrl, assetMode);
    }

}
