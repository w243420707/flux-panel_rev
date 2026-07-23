package com.admin.common.dto;

import lombok.Data;

@Data
public class CloudflareDnsSettingViewDto {
    private Integer enabled;
    private Boolean apiTokenConfigured;
    private String zoneId;
    private String zoneName;
    private Integer ttl;
    private Integer proxied;
    private String recordType;
    private Integer syncIntervalSeconds;
    private Integer autoUpdateNodeIp;
    private Long lastSyncAt;
    private String lastSyncStatus;
    private String lastSyncMessage;
}
