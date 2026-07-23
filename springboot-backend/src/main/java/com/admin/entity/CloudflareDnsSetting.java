package com.admin.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CloudflareDnsSetting extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Integer enabled;

    private String apiToken;

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
