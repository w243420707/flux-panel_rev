package com.admin.service;

import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface NodeService extends IService<Node> {

    R createNode(NodeDto nodeDto);

    R getAllNodes();

    R updateNode(NodeUpdateDto nodeUpdateDto);

    R deleteNode(Long id);

    Node getNodeById(Long id);

    R getInstallCommand(Long id);

    R getInstallCommand(Long id, String publicBaseUrl);

    R getInstallCommand(Long id, String publicBaseUrl, String assetMode);

    boolean refreshRuntimeNodeServerIp(Long id,
                                       String reportedPublicIp,
                                       String reportedPublicIpv4,
                                       String reportedPublicIpv6,
                                       String clientIp);

    /**
     * 异步刷新节点运行时公网 IP，避免阻塞节点 WebSocket 心跳和指标上报。
     */
    void refreshRuntimeNodeServerIpAsync(Long id,
                                         String reportedPublicIp,
                                         String reportedPublicIpv4,
                                         String reportedPublicIpv6,
                                         String clientIp);

}
