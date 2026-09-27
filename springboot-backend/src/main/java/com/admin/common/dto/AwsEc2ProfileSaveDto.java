package com.admin.common.dto;

import lombok.Data;

@Data
public class AwsEc2ProfileSaveDto {
    private Long id;
    private Long accountId;
    private String region;
    private String instanceId;
    private String name;
    private String instanceType;
    private String imageId;
    private String subnetId;
    private String securityGroupIds;
    private String rootVolumeSize;
    private String userData;
    private String tagsJson;
    private String remark;
}
