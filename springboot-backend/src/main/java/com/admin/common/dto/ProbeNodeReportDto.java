package com.admin.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class ProbeNodeReportDto {
    private Long nodeId;
    private String host;
    private Boolean reachable;
    /** Android 探测完成时的本地时间，用于丢弃乱序旧报告。 */
    private Long checkedAt;
    private String message;
    private List<ProbePortReportDto> ports;
}
