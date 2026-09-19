package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("node_vps_usage")
public class NodeVpsUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long nodeId;
    private String hardwareId;
    private String systemId;
    private String macId;
    private String installationId;
    private Boolean weakIdentityApproved;
    private Long startedAt;
    private Long replacedAt;
    private String address;
    private Long uploadBytes;
    private Long downloadBytes;
    private String meterEpoch;
    private Long lastSequence;
    private Long checkpointUploadBytes;
    private Long checkpointDownloadBytes;
    private Long lastReportedAt;
}
