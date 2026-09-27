package com.admin.common.dto;

import lombok.Data;

@Data
public class AwsAccountIdDto {
    private Long id;
    private Long accountId;
    private String region;
}
