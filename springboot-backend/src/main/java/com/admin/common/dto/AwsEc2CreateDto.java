package com.admin.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class AwsEc2CreateDto {
    private Long templateId;
    private Long accountId;
    private String region;
    private String name;
    private String imageId;
    private String instanceType;
    private String availabilityZone;
    private String architecture;
    private Integer rootVolumeSize;
    private Integer diskGiB;
    private String keyName;
    private String userData;
    private Boolean spot;
    private Boolean enableIpv6;
    private Boolean disablePublicIp;
    private Boolean staticIp;
    private String cidr;
    private String password;
    private String subnetId;
    private List<String> securityGroupIds;
    /** Reuse this token when retrying the same creation request. */
    private String clientToken;
}
