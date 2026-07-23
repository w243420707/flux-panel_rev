package com.admin.common.dto;

import lombok.Data;

@Data
public class CloudflareDnsSettingDto {
    private Integer enabled;
    private String apiToken;
    private String zoneId;
    private String zoneName;
    private Integer ttl;
    private Integer proxied;
    private String recordType;
    private Integer syncIntervalSeconds;
    private Integer autoUpdateNodeIp;
}
