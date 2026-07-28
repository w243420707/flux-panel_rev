package com.admin.entity;

import java.io.Serializable;
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

    private String ip;

    private String serverIp;

    private String serverIpv4;

    private String serverIpv6;

    private String version;

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

    private Integer portSta;

    private Integer portEnd;

}
