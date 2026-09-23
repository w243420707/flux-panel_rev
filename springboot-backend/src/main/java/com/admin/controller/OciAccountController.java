package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.OciAccountIdDto;
import com.admin.common.dto.OciAccountSaveDto;
import com.admin.common.lang.R;
import com.admin.service.OciAccountService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/oci/account")
public class OciAccountController {
    @Resource
    private OciAccountService ociAccountService;

    @RequireRole
    @PostMapping("/list")
    public R list() {
        return R.ok(ociAccountService.listAccounts());
    }

    @RequireRole
    @PostMapping("/save")
    public R save(@RequestBody OciAccountSaveDto dto) {
        return ociAccountService.saveAccount(dto);
    }

    @RequireRole
    @PostMapping("/delete")
    public R delete(@RequestBody OciAccountIdDto dto) {
        return dto == null ? R.err("OCI 账号 ID 无效") : ociAccountService.deleteAccount(dto.getId());
    }

    @RequireRole
    @PostMapping("/private-key")
    public R privateKey(@RequestBody OciAccountIdDto dto) {
        try {
            return R.ok(java.util.Map.of("privateKey",
                    ociAccountService.getPrivateKey(dto == null ? null : dto.getId())));
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }

    @RequireRole
    @PostMapping("/test")
    public R test(@RequestBody OciAccountIdDto dto) {
        try {
            return R.ok(ociAccountService.testAccount(dto == null ? null : dto.getId()));
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }

    @RequireRole
    @PostMapping("/instances")
    public R instances(@RequestBody OciAccountIdDto dto) {
        try {
            return R.ok(ociAccountService.listInstances(dto == null ? null : dto.getAccountId()));
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }
}
