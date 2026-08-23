package com.admin.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class ProbeNodeReportDto {
    private Long nodeId;
    private String host;
    private Boolean reachable;
    private String message;
    private List<ProbePortReportDto> ports;
}
