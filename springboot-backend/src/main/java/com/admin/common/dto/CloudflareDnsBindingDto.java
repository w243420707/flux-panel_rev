package com.admin.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class CloudflareDnsBindingDto {
    private Long id;
    private Long tunnelId;
    private String domain;
    private List<Long> nodeIds;
    private Integer useTunnelNodes;
    private String recordType;
}
