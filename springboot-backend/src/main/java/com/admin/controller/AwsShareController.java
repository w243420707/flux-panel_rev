package com.admin.controller;

import com.admin.common.lang.R;
import com.admin.service.impl.AwsWorkflowService;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;

@RestController
@RequestMapping("/api/aws-share/{token}")
public class AwsShareController {
    @Resource private AwsWorkflowService service;
    @GetMapping("/instances")
    public R instances(@PathVariable String token) { return R.ok(service.sharedInstances(token)); }
    @PostMapping("/action")
    public R action(@PathVariable String token, @RequestBody ShareAction dto) {
        return R.ok(service.sharedAction(token, dto.getNodeId(), dto.getAccountId(), dto.getRegion(),
                dto.getInstanceId(), dto.getAction(), dto.getRequestId()));
    }
    @Data public static class ShareAction {
        private Long nodeId;
        private Long accountId;
        private String region;
        private String instanceId;
        private String action;
        private String requestId;
    }
}
