package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.service.AwsAccountService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/aws")
public class AwsAccountResourcesController {
    @Resource private AwsAccountService service;
    @ExceptionHandler(Exception.class) public R sanitizedError(Exception ignored) { return R.err("AWS 分组或代理操作失败，请检查配置后重试"); }
    @RequireRole @PostMapping("/account-group/list") public R groups() { return R.ok(service.listAccountGroups()); }
    @RequireRole @PostMapping("/account-group/save") public R saveGroup(@RequestBody AwsAccountGroupSaveDto dto) { return service.saveAccountGroup(dto); }
    @RequireRole @PostMapping("/account-group/delete") public R deleteGroup(@RequestBody AwsAccountIdDto dto) { return service.deleteAccountGroup(dto == null ? null : dto.getId()); }
    @RequireRole @PostMapping("/proxy/list") public R proxies() { return R.ok(service.listProxyPools()); }
    @RequireRole @PostMapping("/proxy/save") public R saveProxy(@RequestBody AwsProxySaveDto dto) { return service.saveProxyPool(dto); }
    @RequireRole @PostMapping("/proxy/delete") public R deleteProxy(@RequestBody AwsAccountIdDto dto) { return service.deleteProxyPool(dto == null ? null : dto.getId()); }
}
