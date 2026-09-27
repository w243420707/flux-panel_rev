package com.admin.common.dto;

import lombok.Data;

@Data
public class AwsEc2ChangeIpDto {
    private Long accountId;
    private String region;
    private String instanceId;
    /** AUTO chooses EIP when one is attached and DYNAMIC otherwise. */
    private String strategy;
    private Boolean releaseOld;
    /** Optional IPv4 range; Elastic IP allocation is tried at most ten times. */
    private String cidr;
    private Integer maxAttempts;
    private String requestId;
}
