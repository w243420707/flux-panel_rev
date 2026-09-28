package com.admin.common.dto;

import lombok.Data;

/** A direct EC2 share target, used when no panel node is bound yet. */
@Data
public class AwsShareInstanceRefDto {
    private Long accountId;
    private String region;
    private String instanceId;
    private String name;
}
