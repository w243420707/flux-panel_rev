package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.AwsEc2ActionDto;
import com.admin.common.dto.AwsEc2ChangeIpDto;
import com.admin.common.dto.AwsEc2CreateDto;
import com.admin.common.dto.AwsEc2ListDto;
import com.admin.common.dto.AwsEc2ProfileSaveDto;
import com.admin.common.dto.AwsEc2TemplateIdDto;
import com.admin.common.dto.AwsEc2TemplateSaveDto;
import com.admin.common.dto.AwsFailoverConfigDto;
import com.admin.common.dto.AwsShareGroupIdDto;
import com.admin.common.dto.AwsShareGroupSaveDto;
import com.admin.common.lang.R;
import com.admin.service.AwsEc2Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Collections;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/aws")
public class AwsEc2Controller {
    @Resource
    private AwsEc2Service service;
    @Resource private com.admin.service.impl.AwsOperationService operations;
    @Resource private com.admin.service.impl.AwsWorkflowService workflows;

    @RequireRole
    @PostMapping("/ec2/list")
    public R list(@RequestBody(required = false) AwsEc2ListDto dto) {
        try { return R.ok(service.listInstances(dto)); }
        catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }

    @RequireRole
    @PostMapping("/ec2/sync")
    public R sync(@RequestBody(required = false) AwsEc2ListDto dto) {
        try { return R.ok(service.syncInstances(dto)); }
        catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }

    @RequireRole
    @PostMapping("/ec2/action")
    public R action(@RequestBody AwsEc2ActionDto dto) {
        try { return R.ok(operations.submit(dto.getRequestId(), dto.getAccountId(), dto.getRegion(), dto.getInstanceId(),
                dto.getAction(), dto, () -> service.action(dto))); }
        catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }

    @RequireRole
    @PostMapping("/ec2/change-ip")
    public R changeIp(@RequestBody AwsEc2ChangeIpDto dto) {
        try { return R.ok(operations.submit(dto.getRequestId(), dto.getAccountId(), dto.getRegion(), dto.getInstanceId(),
                "change-ip", dto, () -> service.changeIp(dto))); }
        catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }

    @RequireRole
    @PostMapping("/ec2/gfw-check")
    public R gfwCheck(@RequestBody AwsEc2ListDto dto) {
        try { return R.ok(service.gfwCheck(dto)); }
        catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }

    @RequireRole
    @PostMapping("/ec2/create")
    public R create(@RequestBody AwsEc2CreateDto dto) {
        try {
            if (dto.getClientToken() == null || dto.getClientToken().isBlank()) dto.setClientToken(java.util.UUID.randomUUID().toString());
            return R.ok(operations.submit(dto.getClientToken(), dto.getAccountId(), dto.getRegion(), null,
                    "create", dto, () -> service.createInstance(dto)));
        }
        catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }

    @RequireRole
    @PostMapping("/profile/save")
    public R saveProfile(@RequestBody AwsEc2ProfileSaveDto dto) {
        return service.saveProfile(dto);
    }

    @RequireRole
    @PostMapping("/profile/list")
    public R listProfiles(@RequestBody(required = false) AwsEc2ListDto dto) {
        return R.ok(service.listProfiles(dto == null ? null : dto.getAccountId(), dto == null ? null : dto.getRegion()));
    }

    @RequireRole
    @PostMapping("/template/list")
    public R listTemplates(@RequestBody(required = false) AwsEc2ListDto dto) {
        return R.ok(service.listTemplates(dto == null ? null : dto.getAccountId(), dto == null ? null : dto.getRegion()));
    }

    @RequireRole
    @PostMapping("/template/save")
    public R saveTemplate(@RequestBody AwsEc2TemplateSaveDto dto) {
        return service.saveTemplate(dto);
    }

    @RequireRole
    @PostMapping("/template/delete")
    public R deleteTemplate(@RequestBody AwsEc2TemplateIdDto dto) {
        return service.deleteTemplate(dto);
    }

    @RequireRole
    @PostMapping("/share-group/list")
    public R shareGroupList() { return R.ok(service.listShareGroups()); }

    @RequireRole
    @PostMapping("/share-group/save")
    public R shareGroupSave(@RequestBody AwsShareGroupSaveDto dto) { return service.saveShareGroup(dto); }

    @RequireRole
    @PostMapping("/share-group/delete")
    public R shareGroupDelete(@RequestBody AwsShareGroupIdDto dto) { return service.deleteShareGroup(dto); }

    @RequireRole
    @PostMapping("/failover/get")
    public R failoverGet() { return R.ok(service.getFailoverConfig()); }

    @RequireRole
    @PostMapping("/failover/save")
    public R failoverSave(@RequestBody AwsFailoverConfigDto dto) { return service.saveFailoverConfig(dto); }

    @RequireRole
    @PostMapping("/failover/logs")
    public R failoverLogs() { return R.ok(workflows.logs()); }

    @RequireRole
    @PostMapping("/operation/get")
    public R operation(@RequestBody java.util.Map<String, String> dto) { return R.ok(operations.get(dto.get("id"))); }

    @RequireRole
    @PostMapping("/operation/list")
    public R operations() { return R.ok(operations.list()); }

    @RequireRole
    @PostMapping("/operation/acknowledge")
    public R acknowledge(@RequestBody java.util.Map<String, String> dto) { operations.acknowledge(dto.get("id")); return R.ok(); }
}
