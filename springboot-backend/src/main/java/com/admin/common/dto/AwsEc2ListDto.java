package com.admin.common.dto;

import lombok.Data;

@Data
public class AwsEc2ListDto {
    private Long accountId;
    private String region;
    private String instanceId;
    private String keyword;
    private String state;
}
