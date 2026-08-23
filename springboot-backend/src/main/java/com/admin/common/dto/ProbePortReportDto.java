package com.admin.common.dto;

import lombok.Data;

@Data
public class ProbePortReportDto {
    private Integer port;
    private Integer attempts;
    private Integer successCount;
    private Boolean successful;
}
