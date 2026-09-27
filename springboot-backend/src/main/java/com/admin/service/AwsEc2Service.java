package com.admin.service;

import com.admin.common.dto.AwsEc2ActionDto;
import com.admin.common.dto.AwsEc2CreateDto;
import com.admin.common.dto.AwsEc2ChangeIpDto;
import com.admin.common.dto.AwsEc2ListDto;
import com.admin.common.dto.AwsEc2ProfileSaveDto;
import com.admin.common.dto.AwsEc2TemplateIdDto;
import com.admin.common.dto.AwsEc2TemplateSaveDto;
import com.admin.common.dto.AwsShareGroupIdDto;
import com.admin.common.dto.AwsShareGroupSaveDto;
import com.admin.common.dto.AwsFailoverConfigDto;
import com.admin.common.lang.R;
import com.admin.entity.AwsNodeBinding;

import java.util.List;
import java.util.Map;

public interface AwsEc2Service {
    List<Map<String, Object>> listInstances(AwsEc2ListDto dto);
    List<Map<String, Object>> syncInstances(AwsEc2ListDto dto);
    Map<String, Object> action(AwsEc2ActionDto dto);
    Map<String, Object> changeIp(AwsEc2ChangeIpDto dto);
    Map<String, Object> changeIpForNode(Long accountId, String instanceId, String region);
    Map<String, Object> gfwCheck(AwsEc2ListDto dto);
    Map<String, Object> createInstance(AwsEc2CreateDto dto);
    R saveProfile(AwsEc2ProfileSaveDto dto);
    List<Map<String, Object>> listProfiles(Long accountId, String region);
    List<Map<String, Object>> listTemplates(Long accountId, String region);
    R saveTemplate(AwsEc2TemplateSaveDto dto);
    R deleteTemplate(AwsEc2TemplateIdDto dto);
    List<Map<String, Object>> listShareGroups();
    R saveShareGroup(AwsShareGroupSaveDto dto);
    R deleteShareGroup(AwsShareGroupIdDto dto);
    Map<String, Object> getFailoverConfig();
    R saveFailoverConfig(AwsFailoverConfigDto dto);

    /** APK-facing contract; persisted separately until Node gains AWS columns. */
    AwsNodeBinding bindNode(Long nodeId, Long accountId, String instanceId, String region);
    AwsNodeBinding resolveNodeBinding(Long nodeId);
}
