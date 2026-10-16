package com.admin.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CloudflareDnsBinding extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long tunnelId;

    private String domain;

    private String nodeIds;
    private Integer useTunnelNodes;
    private String recordType;
    private Integer smartPoolEnabled;
    private String smartPoolPreferredNodeIds;
    private String smartPoolActiveNodeIds;
    private String smartPoolBackupNodeIds;
    private Long smartPoolLastSwitchAt;
    private Long smartPoolNextRotateAt;
    private Integer rotationEnabled;
    private String rotationGroups;
    private Integer rotationIntervalMinutes;
    private Integer rotationCurrentGroupIndex;
    private Long rotationLastSwitchAt;
    private Long rotationNextSwitchAt;
    private Long lastSyncAt;
    private String lastSyncStatus;
    private String lastSyncMessage;
    private String lastResolvedIps;
}
