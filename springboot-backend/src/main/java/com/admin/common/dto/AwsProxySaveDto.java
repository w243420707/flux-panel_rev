package com.admin.common.dto;

import lombok.Data;

@Data
public class AwsProxySaveDto {
    private Long id;
    private String name;
    @lombok.ToString.Exclude private String url;
    private String remark;
}
