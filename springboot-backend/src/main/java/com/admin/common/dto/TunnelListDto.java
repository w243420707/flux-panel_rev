package com.admin.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class TunnelListDto {

    private Integer id;

    private String name;
    
    /**
     * 入口IP
     */
    private String ip;

    private String outIp;

    private List<Long> inNodeIds;

    private List<Long> outNodeIds;
    
    /**
     * 入口节点端口起始范围
     */
    private Integer inNodePortSta;
    
    /**
     * 入口节点端口结束范围
     */
    private Integer inNodePortEnd;

    /**
     * 隧道类型（1-端口转发，2-隧道转发）
     */
    private Integer type;
    
    /**
     * 协议类型
     */
    private String protocol;
}
