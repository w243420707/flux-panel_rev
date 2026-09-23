package com.admin.entity;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Node extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private String secret;

    /** Token used only by the standalone Android probe callback. */
    private String remoteChangeIpToken;

    /** Minimum delay in minutes between IP change requests after a blocked result. */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Integer changeIpMinIntervalMinutes;

    private Integer oracleNode;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long ociAccountId;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String ociInstanceOcid;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long changeIpLastAttemptAt;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String changeIpLastResult;

    @TableField(exist = false)
    private String remoteChangeIpUrl;

    private String ip;

    private String serverIp;

    private String serverIpv4;

    private String serverIpv6;

    private String version;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long currentUsageId;

    @TableField(exist = false)
    private Object vpsUsage;

    // Only the reboot scheduler updates these columns; ordinary node/IP updates must not overwrite them.
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer rebootIntervalHours;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long rebootNextAt;

    private Integer wallMonitorEnabled;

    private String wallMonitorStatus;

    private Long wallMonitorLastCheckAt;

    private Integer wallMonitorConsecutiveFailures;

    private Integer wallMonitorChinaSuccessCount;

    private Integer wallMonitorChinaTotalCount;

    private Integer wallMonitorGlobalSuccessCount;

    private Integer wallMonitorGlobalTotalCount;

    private Double wallMonitorLatencyMs;

    private String wallMonitorMessage;

    private String wallMonitorExternalStatus;

    private Long wallMonitorExternalLastCheckAt;

    private Integer wallMonitorExternalConsecutiveFailures;

    private String wallMonitorExternalMessage;

    private Integer portSta;

    private Integer portEnd;

}
