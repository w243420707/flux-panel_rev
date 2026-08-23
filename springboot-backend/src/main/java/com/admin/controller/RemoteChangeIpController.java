package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.admin.service.NodeWallMonitorService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * Public callback used by the standalone Android probe.
 * The token is the only credential; no panel login is required.
 */
@RestController
@CrossOrigin
@RequestMapping("/api/remote/change-ip")
public class RemoteChangeIpController {

    @Resource
    private NodeService nodeService;

    @Resource
    private NodeWallMonitorService nodeWallMonitorService;

    @GetMapping("/{token}")
    public R markNodeUnavailable(@PathVariable String token) {
        if (token == null || token.trim().isEmpty() || token.length() > 128) {
            return R.err("无效请求");
        }

        Node node = nodeService.getOne(new QueryWrapper<Node>()
                .eq("remote_change_ip_token", token.trim()));
        if (node == null) {
            return R.err("无效请求");
        }

        return nodeWallMonitorService.markNodeUnavailableByExternalProbe(
                node.getId(), "独立 Android 探针确认配置端口不可达");
    }
}
