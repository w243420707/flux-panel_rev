package com.admin.common.dto;

import lombok.Data;

/** Explicit administrator request to replace one AWS-backed node. */
@Data
public class AwsManualFailoverDto {
    /** Either nodeId or the complete accountId/region/instanceId locator is required. */
    private Long nodeId;
    private Long accountId;
    private String region;
    private String instanceId;
    /** Reusing this value makes a client retry idempotent. */
    private String requestId;
}
