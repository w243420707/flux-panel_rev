package com.admin.common.dto;

import lombok.Data;

@Data
public class AwsShareGroupSaveDto {
    private Long id;
    private String name;
    private Long expiresAt;
    private String permissions;
    private java.util.List<Long> nodeIds;
    private java.util.List<AwsShareInstanceRefDto> instanceRefs;
}
