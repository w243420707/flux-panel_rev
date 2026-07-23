package com.admin.service;

import com.admin.common.dto.CloudflareDnsSettingDto;
import com.admin.common.dto.CloudflareDnsSettingViewDto;
import com.admin.common.lang.R;
import com.admin.entity.CloudflareDnsSetting;
import com.baomidou.mybatisplus.extension.service.IService;

public interface CloudflareDnsSettingService extends IService<CloudflareDnsSetting> {
    CloudflareDnsSetting getCurrentSetting();

    CloudflareDnsSettingViewDto getSettingView();

    R updateSetting(CloudflareDnsSettingDto dto);
}
