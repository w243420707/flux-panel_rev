package com.admin.service.impl;

import com.admin.common.dto.CloudflareDnsSettingDto;
import com.admin.common.dto.CloudflareDnsSettingViewDto;
import com.admin.common.lang.R;
import com.admin.entity.CloudflareDnsSetting;
import com.admin.mapper.CloudflareDnsSettingMapper;
import com.admin.service.CloudflareDnsSettingService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CloudflareDnsSettingServiceImpl extends ServiceImpl<CloudflareDnsSettingMapper, CloudflareDnsSetting> implements CloudflareDnsSettingService {

    private static final int DEFAULT_TTL = 1;
    private static final int DEFAULT_SYNC_INTERVAL_SECONDS = 120;
    private static final int MIN_SYNC_INTERVAL_SECONDS = 60;
    private static final String DEFAULT_RECORD_TYPE = "AUTO";

    @Override
    public CloudflareDnsSetting getCurrentSetting() {
        CloudflareDnsSetting setting = this.getById(1L);
        if (setting != null) {
            return setting;
        }
        setting = this.getOne(new QueryWrapper<CloudflareDnsSetting>().last("LIMIT 1"));
        if (setting != null) {
            return setting;
        }

        setting = new CloudflareDnsSetting();
        setting.setEnabled(0);
        setting.setTtl(DEFAULT_TTL);
        setting.setProxied(0);
        setting.setRecordType(DEFAULT_RECORD_TYPE);
        setting.setSyncIntervalSeconds(DEFAULT_SYNC_INTERVAL_SECONDS);
        setting.setAutoUpdateNodeIp(1);
        setting.setStatus(1);
        setting.setCreatedTime(System.currentTimeMillis());
        setting.setUpdatedTime(System.currentTimeMillis());
        this.save(setting);
        return setting;
    }

    @Override
    public CloudflareDnsSettingViewDto getSettingView() {
        CloudflareDnsSetting setting = getCurrentSetting();
        CloudflareDnsSettingViewDto view = new CloudflareDnsSettingViewDto();
        view.setEnabled(normalizeFlag(setting.getEnabled()));
        view.setApiTokenConfigured(StringUtils.hasText(setting.getApiToken()));
        view.setZoneId(setting.getZoneId());
        view.setZoneName(setting.getZoneName());
        view.setTtl(resolveTtl(setting.getTtl()));
        view.setProxied(0);
        view.setRecordType(resolveRecordType(setting.getRecordType()));
        view.setSyncIntervalSeconds(resolveInterval(setting.getSyncIntervalSeconds()));
        view.setAutoUpdateNodeIp(normalizeFlag(setting.getAutoUpdateNodeIp()));
        view.setLastSyncAt(setting.getLastSyncAt());
        view.setLastSyncStatus(setting.getLastSyncStatus());
        view.setLastSyncMessage(setting.getLastSyncMessage());
        return view;
    }

    @Override
    public R updateSetting(CloudflareDnsSettingDto dto) {
        if (dto == null) {
            return R.err("Cloudflare 配置不能为空");
        }

        CloudflareDnsSetting setting = getCurrentSetting();
        setting.setEnabled(normalizeFlag(dto.getEnabled()));
        if (StringUtils.hasText(dto.getApiToken())) {
            setting.setApiToken(dto.getApiToken().trim());
        }
        setting.setZoneId(trimToNull(dto.getZoneId()));
        setting.setZoneName(trimToNull(dto.getZoneName()));
        setting.setTtl(resolveTtl(dto.getTtl()));
        setting.setProxied(0);
        setting.setRecordType(resolveRecordType(dto.getRecordType()));
        setting.setSyncIntervalSeconds(resolveInterval(dto.getSyncIntervalSeconds()));
        setting.setAutoUpdateNodeIp(normalizeFlag(dto.getAutoUpdateNodeIp()));
        if (setting.getEnabled() != null && setting.getEnabled() == 1) {
            if (!StringUtils.hasText(setting.getApiToken())) {
                return R.err("启用 Cloudflare DNS 前请先配置 API Token");
            }
            if (!StringUtils.hasText(setting.getZoneId())) {
                return R.err("启用 Cloudflare DNS 前请先配置 Zone ID");
            }
        }
        setting.setUpdatedTime(System.currentTimeMillis());
        this.updateById(setting);
        return R.ok(getSettingView());
    }

    private int normalizeFlag(Integer value) {
        return value != null && value == 1 ? 1 : 0;
    }

    private int resolveTtl(Integer ttl) {
        return ttl == null || ttl <= 0 ? DEFAULT_TTL : ttl;
    }

    private int resolveInterval(Integer intervalSeconds) {
        if (intervalSeconds == null || intervalSeconds <= 0) {
            return DEFAULT_SYNC_INTERVAL_SECONDS;
        }
        return Math.max(intervalSeconds, MIN_SYNC_INTERVAL_SECONDS);
    }

    private String resolveRecordType(String recordType) {
        if (!StringUtils.hasText(recordType)) {
            return DEFAULT_RECORD_TYPE;
        }
        String normalized = recordType.trim().toUpperCase();
        if ("A".equals(normalized) || "AAAA".equals(normalized) || "AUTO".equals(normalized)) {
            return normalized;
        }
        return DEFAULT_RECORD_TYPE;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
