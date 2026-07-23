package com.admin.service;

import com.admin.common.dto.CloudflareDnsBindingDto;
import com.admin.common.lang.R;
import com.admin.entity.CloudflareDnsBinding;
import com.baomidou.mybatisplus.extension.service.IService;

public interface CloudflareDnsBindingService extends IService<CloudflareDnsBinding> {
    R getBindingList();

    R saveBinding(CloudflareDnsBindingDto dto);

    R deleteBinding(Long id);

    CloudflareDnsBinding getBindingById(Long id);
}
