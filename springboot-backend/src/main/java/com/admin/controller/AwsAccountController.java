package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.AwsAccountIdDto;
import com.admin.common.dto.AwsAccountSaveDto;
import com.admin.common.dto.AwsAccountImportDto;
import com.admin.common.lang.R;
import com.admin.service.AwsAccountService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/aws/account")
public class AwsAccountController {
    @Resource
    private AwsAccountService service;

    /** Database/SDK exceptions can contain request arguments; never pass those through the global handler. */
    @ExceptionHandler(Exception.class)
    public R sanitizedError(Exception ignored) { return R.err("AWS 账号操作失败，请检查配置后重试"); }

    @RequireRole
    @PostMapping("/list")
    public R list() {
        return R.ok(service.listAccounts());
    }

    @RequireRole
    @PostMapping("/save")
    public R save(@RequestBody AwsAccountSaveDto dto) {
        return service.saveAccount(dto);
    }

    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody AwsAccountIdDto dto) {
        return service.deleteAccount(dto == null ? null : dto.getId());
    }

    @RequireRole
    @PostMapping({"/private-secret", "/private-secrets"})
    public R privateSecret(@RequestBody AwsAccountIdDto dto, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        try {
            return R.ok(service.getPrivateSecrets(dto == null ? null : dto.getId()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    @RequireRole
    @PostMapping("/test")
    public R test(@RequestBody AwsAccountIdDto dto) {
        try {
            Long id = dto == null ? null : (dto.getId() != null ? dto.getId() : dto.getAccountId());
            return R.ok(service.testAccount(id, null));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.err(e.getMessage());
        }
    }

    @RequireRole
    @PostMapping("/quota")
    public R quota(@RequestBody AwsAccountIdDto dto) {
        try {
            return R.ok(service.quota(dto == null ? null : dto.getId() != null ? dto.getId() : dto.getAccountId(), dto == null ? null : dto.getRegion()));
        } catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }

    @RequireRole
    @PostMapping("/import")
    public R importAccounts(@RequestBody AwsAccountImportDto dto) { return service.importAccounts(dto); }
}
