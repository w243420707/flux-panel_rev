package com.admin.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class ProbeSyncReportDto {
    private String deviceId;
    private Integer intervalSeconds;
    private Long checkedAt;
    private List<ProbeNodeReportDto> results;
}
