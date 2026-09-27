package com.admin.common.dto;

import lombok.Data;

@Data
public class AwsAccountImportDto {
    @lombok.ToString.Exclude private String text;
    private Long groupId;
    private String region;
}
