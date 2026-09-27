package com.admin.common.dto;

import lombok.Data;

@Data
public class AwsEc2TemplateSaveDto {
    private Long id;
    private Long accountId;
    private String region;
    private String availabilityZone;
    private String name;
    private String imageId;
    private String instanceType;
    private String keyName;
    private String subnetId;
    private String securityGroupIds;
    private String rootVolumeSize;
    private String architecture;
    private Integer diskGiB;
    private String password;
    private String userData;
    private Boolean spot;
    private Boolean enableIpv6;
    private Boolean disablePublicIp;
    private Boolean staticIp;
    private String cidr;
    private String tagsJson;
    private String remark;
}
