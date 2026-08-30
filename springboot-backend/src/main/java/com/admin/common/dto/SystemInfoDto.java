package com.admin.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 系统信息DTO
 * 对应Go客户端上报的系统信息结构
 */
@Data
public class SystemInfoDto {
    
    /**
     * 主机IP地址
     */
    @JsonProperty("host_ip")
    private String hostIp;
    
    /**
     * 开机时间（秒）
     */
    @JsonProperty("uptime")
    private Long uptime;
    
    /**
     * 接收字节数
     */
    @JsonProperty("bytes_received")
    private Long bytesReceived;
    
    /**
     * 发送字节数
     */
    @JsonProperty("bytes_transmitted")
    private Long bytesTransmitted;
    
    /**
     * CPU使用率（百分比）
     */
    @JsonProperty("cpu_usage")
    private Double cpuUsage;
    
    /**
     * 内存使用率（百分比）
     */
    @JsonProperty("memory_usage")
    private Double memoryUsage;

    /**
     * Swap使用率（百分比）
     */
    @JsonProperty("swap_usage")
    private Double swapUsage;

    /**
     * 已使用Swap（字节）
     */
    @JsonProperty("swap_used")
    private Long swapUsed;

    /**
     * Swap总量（字节）
     */
    @JsonProperty("swap_total")
    private Long swapTotal;
    
    /**
     * 上报时间戳
     */
    private Long timestamp;
    
    public SystemInfoDto() {
        this.timestamp = System.currentTimeMillis();
    }
}
