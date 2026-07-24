package com.admin.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.admin.common.dto.NodeDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.lang.R;
import com.admin.entity.Forward;
import com.admin.common.utils.TunnelNodeUtil;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.ViteConfig;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import com.admin.service.CloudflareDnsSettingService;
import com.admin.service.CloudflareDnsSyncService;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 节点服务实现类
 * 提供节点的增删改查功能，包括节点创建、更新、删除和查询操作
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@Service
public class NodeServiceImpl extends ServiceImpl<NodeMapper, Node> implements NodeService {

    // ========== 常量定义 ==========
    
    /** 节点默认状态：启用 */
    private static final int NODE_STATUS_ACTIVE = 0;
    
    /** 成功响应消息 */
    private static final String SUCCESS_CREATE_MSG = "节点创建成功";
    private static final String SUCCESS_UPDATE_MSG = "节点更新成功";
    private static final String SUCCESS_DELETE_MSG = "节点删除成功";
    
    /** 错误响应消息 */
    private static final String ERROR_CREATE_MSG = "节点创建失败";
    private static final String ERROR_UPDATE_MSG = "节点更新失败";
    private static final String ERROR_DELETE_MSG = "节点删除失败";
    private static final String ERROR_NODE_NOT_FOUND = "节点不存在";
    
    /** 隧道使用检查相关消息 */
    private static final String ERROR_IN_NODE_IN_USE = "该节点还有 %d 个隧道作为入口节点在使用，请先删除相关隧道";
    private static final String ERROR_OUT_NODE_IN_USE = "该节点还有 %d 个隧道作为出口节点在使用，请先删除相关隧道";
    
    /** 端口范围验证相关消息 */
    private static final String ERROR_PORT_STA_REQUIRED = "起始端口不能为空";
    private static final String ERROR_PORT_END_REQUIRED = "结束端口不能为空";
    private static final String ERROR_PORT_RANGE_INVALID = "端口必须在1-65535范围内";
    private static final String ERROR_PORT_ORDER_INVALID = "结束端口不能小于起始端口";

    // ========== 依赖注入 ==========
    
    @Resource
    private TunnelMapper tunnelMapper;

    @Resource
    @Lazy
    private TunnelService tunnelService;

    @Resource
    @Lazy
    private ForwardService forwardService;

    @Resource
    ViteConfigService viteConfigService;

    @Resource
    @Lazy
    private CloudflareDnsSettingService cloudflareDnsSettingService;

    @Resource
    @Lazy
    private CloudflareDnsSyncService cloudflareDnsSyncService;


    // ========== 公共接口实现 ==========

    /**
     * 创建新节点
     * 
     * @param nodeDto 节点创建数据传输对象
     * @return 创建结果响应
     */
    @Override
    public R createNode(NodeDto nodeDto) {
        Node node = buildNewNode(nodeDto);
        boolean result = this.save(node);
        return result ? R.ok(SUCCESS_CREATE_MSG) : R.err(ERROR_CREATE_MSG);
    }



    /**
     * 获取所有节点列表
     * 注意：返回结果中会隐藏节点密钥信息
     * 
     * @return 包含所有节点的响应对象
     */
    @Override
    public R getAllNodes() {
        List<Node> nodeList = this.list();
        hideNodeSecrets(nodeList);
        return R.ok(nodeList);
    }

    /**
     * 更新节点信息
     * 
     * @param nodeUpdateDto 节点更新数据传输对象
     * @return 更新结果响应
     */
    @Override
    public R updateNode(NodeUpdateDto nodeUpdateDto) {
        // 1. 验证节点是否存在
        if (!isNodeExists(nodeUpdateDto.getId())) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        // 2. 构建更新对象并执行更新
        Node updateNode = buildUpdateNode(nodeUpdateDto);
        boolean result = this.updateById(updateNode);
        if (!result) {
            return R.err(ERROR_UPDATE_MSG);
        }

        // 更新隧道入口ip
        List<Tunnel> inNodeId = tunnelService.list(new QueryWrapper<Tunnel>().eq("in_node_id", updateNode.getId()));
        if (!inNodeId.isEmpty()) {
            for (Tunnel tunnel : inNodeId) {
                tunnel.setInIp(resolveNodeAddress(updateNode, false));
            }
            tunnelService.updateBatchById(inNodeId);
        }

        // 更新服务器出口ip
        List<Tunnel> outNodeId = tunnelService.list(new QueryWrapper<Tunnel>().eq("out_node_id", updateNode.getId()));
        if (!outNodeId.isEmpty()) {
            for (Tunnel tunnel : outNodeId) {
                tunnel.setOutIp(resolveNodeAddress(updateNode, true));
            }
            tunnelService.updateBatchById(outNodeId);
        }

        refreshMultiNodeTunnelIps(updateNode.getId());
        refreshForwardConfigsByNode(updateNode.getId());
        syncCloudflareDnsByNode(updateNode.getId(), "node-update");

        return R.ok(SUCCESS_UPDATE_MSG);
    }

    /**
     * 删除节点
     * 删除前会检查是否有隧道正在使用该节点
     * 
     * @param id 节点ID
     * @return 删除结果响应
     */
    private void refreshMultiNodeTunnelIps(Long nodeId) {
        List<Tunnel> tunnels = tunnelService.list();
        for (Tunnel tunnel : tunnels) {
            boolean changed = false;
            if (TunnelNodeUtil.containsInNode(tunnel, nodeId)) {
                tunnel.setInIp(joinNodeIps(TunnelNodeUtil.getInNodeIds(tunnel), false));
                changed = true;
            }
            if (TunnelNodeUtil.containsOutNode(tunnel, nodeId)) {
                tunnel.setOutIp(joinNodeIps(TunnelNodeUtil.getOutNodeIds(tunnel), true));
                changed = true;
            }
            if (changed) {
                tunnelService.updateById(tunnel);
            }
        }
    }

    private void refreshForwardConfigsByNode(Long nodeId) {
        List<Forward> forwards = forwardService.list();
        for (Forward forward : forwards) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel == null) {
                continue;
            }
            if (!TunnelNodeUtil.containsInNode(tunnel, nodeId) && !TunnelNodeUtil.containsOutNode(tunnel, nodeId)) {
                continue;
            }
            forwardService.refreshForwardConfig(forward, null);
        }
    }

    private String joinNodeIps(List<Long> nodeIds, boolean serverIp) {
        StringBuilder builder = new StringBuilder();
        for (Long id : nodeIds) {
            Node node = this.getById(id);
            if (node == null) {
                continue;
            }
            String ip = resolveNodeAddress(node, serverIp);
            if (StrUtil.isBlank(ip)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(ip);
        }
        return builder.toString();
    }

    private String normalizeRuntimeIp(String clientIp) {
        if (StrUtil.isBlank(clientIp)) {
            return null;
        }
        String value = clientIp.trim();
        int commaIndex = value.indexOf(',');
        if (commaIndex >= 0) {
            value = value.substring(0, commaIndex).trim();
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int scopeIndex = value.indexOf('%');
        if (scopeIndex >= 0) {
            value = value.substring(0, scopeIndex);
        }
        return value;
    }

    private boolean isPublicIp(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }

            byte[] bytes = address.getAddress();
            if (address instanceof Inet4Address && bytes.length == 4) {
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                int third = bytes[2] & 0xff;
                return !(first == 0
                        || first == 10
                        || first == 127
                        || first == 169 && second == 254
                        || first == 172 && second >= 16 && second <= 31
                        || first == 192 && second == 168
                        || first == 100 && second >= 64 && second <= 127
                        || first == 192 && second == 0 && (third == 0 || third == 2)
                        || first == 198 && (second == 18 || second == 19)
                        || first == 198 && second == 51 && third == 100
                        || first == 203 && second == 0 && third == 113
                        || first >= 224);
            }

            if (address instanceof Inet6Address && bytes.length == 16) {
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                return !(first == 0xfc
                        || first == 0xfd
                        || first == 0x20 && second == 0x01 && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8);
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @Override
    public R deleteNode(Long id) {
        // 1. 验证节点是否存在
        if (!isNodeExists(id)) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        // 2. 检查节点使用情况
        R usageCheckResult = checkNodeUsage(id);
        if (usageCheckResult.getCode() != 0) {
            return usageCheckResult;
        }

        // 3. 执行删除操作
        boolean result = this.removeById(id);
        return result ? R.ok(SUCCESS_DELETE_MSG) : R.err(ERROR_DELETE_MSG);
    }

    /**
     * 根据ID获取节点信息
     * 
     * @param id 节点ID
     * @return 节点对象
     * @throws RuntimeException 当节点不存在时抛出异常
     */
    @Override
    public Node getNodeById(Long id) {
        Node node = this.getById(id);
        if (node == null) {
            throw new RuntimeException(ERROR_NODE_NOT_FOUND);
        }
        return node;
    }

    @Override
    public boolean refreshRuntimeNodeServerIp(Long id, String reportedPublicIp, String clientIp) {
        if (id == null || (StrUtil.isBlank(reportedPublicIp) && StrUtil.isBlank(clientIp))) {
            return false;
        }
        Integer autoUpdateNodeIp = cloudflareDnsSettingService.getCurrentSetting().getAutoUpdateNodeIp();
        boolean autoUpdateEnabled = autoUpdateNodeIp != null && autoUpdateNodeIp == 1;

        String normalizedIp = resolveRuntimeNodeServerIp(reportedPublicIp, clientIp);
        if (StrUtil.isBlank(normalizedIp) || !isPublicIp(normalizedIp)) {
            return false;
        }

        Node node = this.getById(id);
        if (node == null) {
            return false;
        }

        String oldServerIp = normalizeNodeAddress(node.getServerIp());
        String oldEntryIp = normalizeNodeAddress(node.getIp());
        if (!autoUpdateEnabled && StrUtil.isNotBlank(oldServerIp)) {
            return false;
        }

        boolean entryIpFollowsServer = StrUtil.isBlank(oldEntryIp) || Objects.equals(oldEntryIp, oldServerIp);
        boolean changed = false;
        if (!Objects.equals(normalizedIp, oldServerIp)) {
            node.setServerIp(normalizedIp);
            changed = true;
        }
        if (entryIpFollowsServer && !Objects.equals(normalizedIp, oldEntryIp)) {
            node.setIp(normalizedIp);
            changed = true;
        }
        if (!changed) {
            return false;
        }

        node.setUpdatedTime(System.currentTimeMillis());
        boolean updated = this.updateById(node);
        if (updated) {
            refreshMultiNodeTunnelIps(id);
            refreshForwardConfigsByNode(id);
            syncCloudflareDnsByNode(id, "node-runtime-ip");
        }
        return updated;
    }

    // ========== 私有辅助方法 ==========

    private String resolveRuntimeNodeServerIp(String reportedPublicIp, String clientIp) {
        String normalizedReportedIp = normalizeRuntimeIp(reportedPublicIp);
        if (StrUtil.isNotBlank(normalizedReportedIp) && isPublicIp(normalizedReportedIp)) {
            return normalizedReportedIp;
        }

        String normalizedClientIp = normalizeRuntimeIp(clientIp);
        if (StrUtil.isNotBlank(normalizedClientIp) && isPublicIp(normalizedClientIp)) {
            return normalizedClientIp;
        }

        return null;
    }

    private void syncCloudflareDnsByNode(Long nodeId, String trigger) {
        try {
            cloudflareDnsSyncService.syncBindingsByNode(nodeId, trigger);
        } catch (Exception ignored) {
        }
    }

    private String resolveNodeAddress(Node node, boolean preferServerIp) {
        if (node == null) {
            return null;
        }
        String primary = preferServerIp ? node.getServerIp() : node.getIp();
        if (StrUtil.isBlank(primary)) {
            primary = preferServerIp ? node.getIp() : node.getServerIp();
        }
        return normalizeNodeAddress(primary);
    }

    private String normalizeNodeAddress(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 构建新节点对象
     * 
     * @param nodeDto 节点创建DTO
     * @return 构建完成的节点对象
     */
    private Node buildNewNode(NodeDto nodeDto) {
        Node node = new Node();
        BeanUtils.copyProperties(nodeDto, node);
        normalizeNodeAddressFields(node);

        // 验证端口范围
        validatePortRange(node.getPortSta(), node.getPortEnd());
        
        // 设置默认属性
        node.setSecret(IdUtil.simpleUUID());
        node.setStatus(NODE_STATUS_ACTIVE);
        
        // 设置时间戳
        long currentTime = System.currentTimeMillis();
        node.setCreatedTime(currentTime);
        node.setUpdatedTime(currentTime);
        
        return node;
    }

    /**
     * 构建节点更新对象
     * 
     * @param nodeUpdateDto 节点更新DTO
     * @return 构建完成的更新对象
     */
    private Node buildUpdateNode(NodeUpdateDto nodeUpdateDto) {
        Node node = new Node();
        node.setId(nodeUpdateDto.getId());
        node.setName(nodeUpdateDto.getName());
        node.setIp(nodeUpdateDto.getIp());
        node.setServerIp(nodeUpdateDto.getServerIp());
        node.setPortSta(nodeUpdateDto.getPortSta());
        node.setPortEnd(nodeUpdateDto.getPortEnd());
        normalizeNodeAddressFields(node);

        // 验证端口范围
        validatePortRange(node.getPortSta(), node.getPortEnd());
        
        node.setUpdatedTime(System.currentTimeMillis());
        return node;
    }

    private void normalizeNodeAddressFields(Node node) {
        node.setIp(normalizeNodeAddress(node.getIp()));
        node.setServerIp(normalizeNodeAddress(node.getServerIp()));
    }

    /**
     * 隐藏节点列表中的密钥信息
     * 
     * @param nodeList 节点列表
     */
    private void hideNodeSecrets(List<Node> nodeList) {
        nodeList.forEach(node -> node.setSecret(null));
    }

    /**
     * 检查节点是否存在
     * 
     * @param nodeId 节点ID
     * @return 节点是否存在
     */
    private boolean isNodeExists(Long nodeId) {
        return this.getById(nodeId) != null;
    }

    /**
     * 检查节点使用情况
     * 验证是否有隧道正在使用该节点作为入口或出口节点
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkNodeUsage(Long nodeId) {
        // 检查入口节点使用情况
        R inNodeCheckResult = checkInNodeUsage(nodeId);
        if (inNodeCheckResult.getCode() != 0) {
            return inNodeCheckResult;
        }

        // 检查出口节点使用情况
        return checkOutNodeUsage(nodeId);
    }

    /**
     * 检查节点作为入口节点的使用情况
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkInNodeUsage(Long nodeId) {
        long tunnelCount = tunnelService.list().stream()
                .filter(tunnel -> TunnelNodeUtil.containsInNode(tunnel, nodeId))
                .count();
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_IN_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        return R.ok();
    }

    private R checkOutNodeUsage(Long nodeId) {
        long tunnelCount = tunnelService.list().stream()
                .filter(tunnel -> TunnelNodeUtil.containsOutNode(tunnel, nodeId))
                .count();
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_OUT_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        return R.ok();
    }

    private R checkInNodeUsageLegacy(Long nodeId) {
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("in_node_id", nodeId);
        
        long tunnelCount = tunnelMapper.selectCount(query);
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_IN_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 检查节点作为出口节点的使用情况
     * 
     * @param nodeId 节点ID
     * @return 检查结果响应
     */
    private R checkOutNodeUsageLegacy(Long nodeId) {
        QueryWrapper<Tunnel> query = new QueryWrapper<>();
        query.eq("out_node_id", nodeId);
        
        long tunnelCount = tunnelMapper.selectCount(query);
        if (tunnelCount > 0) {
            String errorMsg = String.format(ERROR_OUT_NODE_IN_USE, tunnelCount);
            return R.err(errorMsg);
        }
        
        return R.ok();
    }

    /**
     * 获取节点安装命令
     * 根据节点信息生成对应的安装命令
     * 
     * @param id 节点ID
     * @return 包含安装命令的响应对象
     */
    @Override
    public R getInstallCommand(Long id) {
        // 1. 验证节点是否存在
        Node node = this.getById(id);
        if (node == null) {
            return R.err(ERROR_NODE_NOT_FOUND);
        }

        // 2. 构建安装命令
        return buildInstallCommand(node);
    }

    /**
     * 构建节点安装命令
     * 
     * @param node 节点对象
     * @return 格式化的安装命令
     */
    private R buildInstallCommand(Node node) {
        ViteConfig viteConfig = viteConfigService.getOne(new QueryWrapper<ViteConfig>().eq("name", "ip"));
        if (viteConfig == null) return R.err("请先前往网站配置中设置ip");

        StringBuilder command = new StringBuilder();
        
        // 第一部分：下载安装脚本  
        command.append("curl -L https://raw.githubusercontent.com/w243420707/flux-panel_rev/refs/heads/main/install.sh")
               .append(" -o ./install.sh && chmod +x ./install.sh && ");
        
        // 处理服务器地址，如果是IPv6需要添加方括号
        String processedServerAddr = processServerAddress(viteConfig.getValue());
        
        // 第二部分：执行安装脚本（去掉-u参数）
        command.append("./install.sh")
               .append(" -a ").append(processedServerAddr)  // 服务器地址
               .append(" -s ").append(node.getSecret());    // 节点密钥
        
        return R.ok(command.toString());
    }

    /**
     * 处理服务器地址，确保IPv6地址被方括号包裹
     * 
     * @param serverAddr 原始服务器地址，格式可能为 host:port
     * @return 处理后的服务器地址
     */
    private String processServerAddress(String serverAddr) {
        if (StrUtil.isBlank(serverAddr)) {
            return serverAddr;
        }
        
        // 如果已经被方括号包裹，直接返回
        if (serverAddr.startsWith("[")) {
            return serverAddr;
        }
        
        // 查找最后一个冒号，分离主机和端口
        int lastColonIndex = serverAddr.lastIndexOf(':');
        if (lastColonIndex == -1) {
            // 没有端口号，直接检查是否需要包裹
            return isIPv6Address(serverAddr) ? "[" + serverAddr + "]" : serverAddr;
        }
        
        String host = serverAddr.substring(0, lastColonIndex);
        String port = serverAddr.substring(lastColonIndex);
        
        // 检查主机部分是否为IPv6地址
        if (isIPv6Address(host)) {
            return "[" + host + "]" + port;
        }
        
        return serverAddr;
    }

    /**
     * 判断是否为IPv6地址
     * 
     * @param address 地址字符串（不包含端口号）
     * @return 是否为IPv6地址
     */
    private boolean isIPv6Address(String address) {
        // IPv6地址包含多个冒号，至少2个
        if (!address.contains(":")) {
            return false;
        }
        
        // 计算冒号数量，IPv6地址至少有2个冒号
        long colonCount = address.chars().filter(ch -> ch == ':').count();
        return colonCount >= 2;
    }

    /**
     * 验证端口范围的有效性
     * 
     * @param portSta 起始端口
     * @param portEnd 结束端口
     * @throws RuntimeException 当端口范围无效时抛出异常
     */
    private void validatePortRange(Integer portSta, Integer portEnd) {
        // 检查起始端口是否为空
        if (portSta == null) {
            throw new RuntimeException(ERROR_PORT_STA_REQUIRED);
        }
        
        // 检查结束端口是否为空
        if (portEnd == null) {
            throw new RuntimeException(ERROR_PORT_END_REQUIRED);
        }
        
        // 检查端口范围是否在有效区间内
        if (portSta < 1 || portSta > 65535 || portEnd < 1 || portEnd > 65535) {
            throw new RuntimeException(ERROR_PORT_RANGE_INVALID);
        }
        
        // 检查端口顺序是否正确
        if (portEnd < portSta) {
            throw new RuntimeException(ERROR_PORT_ORDER_INVALID);
        }
    }

}

