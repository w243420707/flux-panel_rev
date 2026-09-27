package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.PanelUpdateSettingsRequest;
import com.admin.common.lang.R;
import com.admin.service.PanelUpdateService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/** Administrator-only panel update settings and status endpoints. */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/update")
public class PanelUpdateController {
    @Resource
    private PanelUpdateService panelUpdateService;

    @RequireRole
    @GetMapping("/settings")
    public R getSettings() {
        return panelUpdateService.getSettings();
    }

    @RequireRole
    @PutMapping("/settings")
    public R saveSettings(@RequestBody PanelUpdateSettingsRequest request) {
        return panelUpdateService.saveSettings(request);
    }

    @RequireRole
    @PostMapping("/check")
    public R checkNow() {
        return panelUpdateService.checkNow();
    }

    @RequireRole
    @GetMapping("/status")
    public R getStatus() {
        return panelUpdateService.getStatus();
    }
}
