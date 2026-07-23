package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.cloudflare.CloudflareApiClient;
import com.admin.common.dto.CloudflareDnsBindingDto;
import com.admin.common.dto.CloudflareDnsSettingDto;
import com.admin.common.lang.R;
import com.admin.entity.CloudflareDnsBinding;
import com.admin.entity.CloudflareDnsSetting;
import com.admin.service.CloudflareDnsBindingService;
import com.admin.service.CloudflareDnsSettingService;
import com.admin.service.CloudflareDnsSyncService;
import com.alibaba.fastjson.JSONObject;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/cloudflare-dns")
public class CloudflareDnsController extends BaseController {

    @Resource
    private CloudflareDnsSettingService cloudflareDnsSettingService;

    @Resource
    private CloudflareDnsBindingService cloudflareDnsBindingService;

    @Resource
    private CloudflareDnsSyncService cloudflareDnsSyncService;

    @Resource
    private CloudflareApiClient cloudflareApiClient;

    @LogAnnotation
    @RequireRole
    @PostMapping("/setting")
    public R getSetting() {
        return R.ok(cloudflareDnsSettingService.getSettingView());
    }

    @RequireRole
    @PostMapping("/setting/update")
    public R updateSetting(@RequestBody CloudflareDnsSettingDto dto) {
        R result = cloudflareDnsSettingService.updateSetting(dto);
        if (result.getCode() != 0 || dto == null || dto.getEnabled() == null || dto.getEnabled() != 1) {
            return result;
        }

        R syncResult = cloudflareDnsSyncService.syncAllBindings("setting-update");
        if (syncResult.getCode() != 0) {
            result.setMsg("配置已保存，但 DNS 同步失败: " + syncResult.getMsg());
        }
        return result;
    }

    @RequireRole
    @PostMapping("/test")
    public R testSetting(@RequestBody(required = false) CloudflareDnsSettingDto dto) {
        CloudflareDnsSetting setting = cloudflareDnsSettingService.getCurrentSetting();
        String apiToken = dto != null && StringUtils.hasText(dto.getApiToken())
                ? dto.getApiToken().trim()
                : setting.getApiToken();
        String zoneId = dto != null && StringUtils.hasText(dto.getZoneId())
                ? dto.getZoneId().trim()
                : setting.getZoneId();

        if (!StringUtils.hasText(apiToken)) {
            return R.err("Cloudflare API Token 未配置");
        }
        if (!StringUtils.hasText(zoneId)) {
            return R.err("Cloudflare Zone ID 未配置");
        }

        JSONObject response = cloudflareApiClient.getZone(zoneId, apiToken);
        JSONObject zone = response.getJSONObject("result");
        Map<String, Object> data = new HashMap<>();
        if (zone != null) {
            data.put("id", zone.getString("id"));
            data.put("name", zone.getString("name"));
            data.put("status", zone.getString("status"));
        }
        return R.ok(data);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/binding/list")
    public R listBindings() {
        return cloudflareDnsBindingService.getBindingList();
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/binding/save")
    public R saveBinding(@RequestBody CloudflareDnsBindingDto dto) {
        R result = cloudflareDnsBindingService.saveBinding(dto);
        if (result.getCode() != 0 || !(result.getData() instanceof CloudflareDnsBinding)) {
            return result;
        }

        CloudflareDnsBinding binding = (CloudflareDnsBinding) result.getData();
        CloudflareDnsSetting setting = cloudflareDnsSettingService.getCurrentSetting();
        if (setting.getEnabled() != null && setting.getEnabled() == 1) {
            R syncResult = cloudflareDnsSyncService.syncBinding(binding.getId(), "binding-save");
            if (syncResult.getCode() != 0) {
                result.setMsg("DNS 绑定已保存，但同步失败: " + syncResult.getMsg());
            }
        }
        return result;
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/binding/delete")
    public R deleteBinding(@RequestBody Map<String, Object> params) {
        Long id = parseId(params.get("id"));
        if (id == null) {
            return R.err("DNS 绑定 ID 无效");
        }
        return cloudflareDnsSyncService.deleteBindingAndRecords(id);
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/binding/sync")
    public R syncBinding(@RequestBody Map<String, Object> params) {
        Long id = parseId(params.get("id"));
        if (id == null) {
            return R.err("DNS 绑定 ID 无效");
        }
        return cloudflareDnsSyncService.syncBinding(id, "manual-binding");
    }

    @LogAnnotation
    @RequireRole
    @PostMapping("/sync")
    public R syncAll() {
        return cloudflareDnsSyncService.syncAllBindings("manual-all");
    }

    private Long parseId(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }
}
