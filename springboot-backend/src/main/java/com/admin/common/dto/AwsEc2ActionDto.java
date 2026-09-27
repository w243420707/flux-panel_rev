package com.admin.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class AwsEc2ActionDto {
    private Long accountId;
    private String region;
    private String instanceId;
    private String action;
    private String name;
    private String userData;
    private String instanceType;
    private Integer rootVolumeSize;
    private String rootVolumeType;
    private Integer rootVolumeIops;
    private Integer rootVolumeThroughput;
    private String imageId;
    private String clientToken;
    private String requestId;
    private List<String> ipv6Addresses;
}
