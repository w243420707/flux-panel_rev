package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.dto.GostConfigDto;
import com.admin.common.lang.R;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.common.utils.AESCrypto;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.TunnelNodeUtil;
import com.admin.entity.*;
import com.admin.mapper.SiteTrafficMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * <p>
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * <p>
 * 并发安全解决方案：
 * 1. 使用UpdateWrapper进行数据库层面的原子更新操作，避免读取-修改-写入的竞态条件
 * 2. 数据库原子自增可以安全处理并发上报，避免在 Java 层为每个用户和转发串行排队
 */
@RestController
@RequestMapping("/flow")
@CrossOrigin
@Slf4j
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    private static final long NODE_SECRET_CACHE_TTL_MS = 30 * 1000L;
    private static final long LIMIT_CHECK_INTERVAL_MS = 15 * 1000L;
    private static final ConcurrentHashMap<String, CachedNodeSecret> NODE_SECRET_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LIMIT_CHECK_TIMES = new ConcurrentHashMap<>();

    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> CRYPTO_CACHE = new ConcurrentHashMap<>();

    @Resource
    CheckGostConfigAsync checkGostConfigAsync;

    @Resource
    SiteTrafficMapper siteTrafficMapper;

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() {
            return encrypted;
        }

        public void setEncrypted(boolean encrypted) {
            this.encrypted = encrypted;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }

    @PostMapping("/config")
    @LogAnnotation
    public String config(@RequestBody String rawData, String secret) {
        Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
        if (node == null) return SUCCESS_RESPONSE;

        try {
            // 尝试解密数据
            String decryptedData = decryptIfNeeded(rawData, secret);

            // 解析为GostConfigDto
            GostConfigDto gostConfigDto = JSON.parseObject(decryptedData, GostConfigDto.class);
            checkGostConfigAsync.cleanNodeConfigs(node.getId().toString(), gostConfigDto);

            log.info("🔓 节点 {} 配置数据接收成功{}", node.getId(), isEncryptedMessage(rawData) ? "（已解密）" : "");

        } catch (Exception e) {
            log.error("处理节点 {} 配置数据失败: {}", node.getId(), e.getMessage());
        }

        return SUCCESS_RESPONSE;
    }

    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }

    /**
     * 处理流量数据上报
     *
     * @param rawData 原始数据（可能是加密的）
     * @param secret  节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    @LogAnnotation
    @Transactional(rollbackFor = Exception.class)
    public String uploadFlowData(@RequestBody String rawData, String secret) {
        // 1. 验证节点权限
        if (!isValidNode(secret)) {
            return SUCCESS_RESPONSE;
        }

        // 2. 尝试解密数据
        String decryptedData = decryptIfNeeded(rawData, secret);

        // 3. 解析为FlowDto列表
        FlowDto flowDataList = JSONObject.parseObject(decryptedData, FlowDto.class);
        if (Objects.equals(flowDataList.getN(), "web_api")) {
            return SUCCESS_RESPONSE;
        }

        // 流量上报频率很高，默认只在 DEBUG 级别记录，避免刷屏。
        log.debug("节点上报流量: service={}, upload={}, download={}", flowDataList.getN(), flowDataList.getU(), flowDataList.getD());
        // 4. 处理流量数据
        return processFlowData(flowDataList);
    }

    /**
     * 检测消息是否为加密格式
     */
    private boolean isEncryptedMessage(String data) {
        try {
            JSONObject json = JSON.parseObject(data);
            return json.getBooleanValue("encrypted");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据需要解密数据
     */
    private String decryptIfNeeded(String rawData, String secret) {
        if (rawData == null || rawData.trim().isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(rawData, EncryptedMessage.class);

            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(secret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return rawData;
                }

                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("数据未加密或解密失败，使用原始数据: {}", e.getMessage());
        }

        return rawData;
    }

    /**
     * 获取或创建加密器实例
     */
    private AESCrypto getOrCreateCrypto(String secret) {
        return CRYPTO_CACHE.computeIfAbsent(secret, AESCrypto::create);
    }

    /**
     * 处理流量数据的核心逻辑
     */
    private String processFlowData(FlowDto flowDataList) {
        if (flowDataList.getD() == null || flowDataList.getU() == null
                || flowDataList.getD() < 0 || flowDataList.getU() < 0) {
            throw new IllegalArgumentException("流量数据无效");
        }

        String[] serviceIds = parseServiceName(flowDataList.getN());
        String forwardId = serviceIds[0];
        String userId = serviceIds[1];
        String userTunnelId = serviceIds[2];

        Forward forward = forwardService.getById(forwardId);

        // 同一份流量上报只查询一次隧道，避免重复读取。
        Tunnel tunnel = forward == null ? null : tunnelService.getById(forward.getTunnelId());

        // 获取流量计费类型
        int flowType = getFlowType(tunnel);

        //  处理流量倍率及单双向计算
        FlowDto flowStats = filterFlowData(flowDataList, tunnel, flowType);

        // 先更新所有流量统计 - 确保流量数据的一致性
        requireUpdated("转发流量", updateForwardFlow(forwardId, flowStats));
        requireUpdated("用户流量", updateUserFlow(userId, flowStats));
        updateUserTunnelFlow(userTunnelId, flowStats);
        requireUpdated("站点累计流量", siteTrafficMapper.increment(
                flowStats.getD(), flowStats.getU(), System.currentTimeMillis()));

        // 7. 检查和服务暂停操作
        String name = buildServiceName(forwardId, userId, userTunnelId);
        if (!Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) { // 非管理员的转发需要检测流量限制
            if (isLimitCheckDue("user:" + userId)) {
                checkUserRelatedLimits(userId, name);
            }
            if (isLimitCheckDue("tunnel:" + userTunnelId)) {
                checkUserTunnelRelatedLimits(userTunnelId, name, userId);
            }
        }

        return SUCCESS_RESPONSE;
    }

    private void checkUserRelatedLimits(String userId, String name) {

        // 重新查询用户以获取最新的流量数据
        User updatedUser = userService.getById(userId);
        if (updatedUser == null) return;

        // 检查用户总流量限制
        long userFlowLimit = updatedUser.getFlow() * BYTES_TO_GB;
        long userCurrentFlow = updatedUser.getInFlow() + updatedUser.getOutFlow();
        if (userFlowLimit < userCurrentFlow) {
            pauseAllUserServices(userId, name);
            return;
        }

        // 检查用户到期时间
        if (updatedUser.getExpTime() != null && updatedUser.getExpTime() <= new Date().getTime()) {
            pauseAllUserServices(userId, name);
            return;
        }

        // 检查用户状态
        if (updatedUser.getStatus() != 1) {
            pauseAllUserServices(userId, name);
        }
    }

    public void pauseAllUserServices(String userId, String name) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userId));
        pauseService(forwardList, name);
    }

    public void checkUserTunnelRelatedLimits(String userTunnelId, String name, String userId) {

        UserTunnel userTunnel = userTunnelService.getById(userTunnelId);
        if (userTunnel == null) return;
        long flow = userTunnel.getInFlow() + userTunnel.getOutFlow();
        if (flow >= userTunnel.getFlow() *  BYTES_TO_GB) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
            return;
        }

        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
            return;
        }

        if (userTunnel.getStatus() != 1) {
            pauseSpecificForward(userTunnel.getTunnelId(), name, userId);
        }


    }

    private void pauseSpecificForward(Integer tunnelId, String name, String userId) {
        List<Forward> forwardList = forwardService.list(new QueryWrapper<Forward>().eq("tunnel_id", tunnelId).eq("user_id", userId));
        pauseService(forwardList, name);
    }

    public void pauseService(List<Forward> forwardList, String name) {
        for (Forward forward : forwardList) {
            String serviceName = resolveForwardServiceName(forward, name);
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null){
                for (Long inNodeId : TunnelNodeUtil.getInNodeIds(tunnel)) {
                    GostUtil.PauseService(inNodeId, serviceName);
                }
                if (tunnel.getType() == 2){
                    for (Long outNodeId : TunnelNodeUtil.getOutNodeIds(tunnel)) {
                        GostUtil.PauseRemoteService(outNodeId, serviceName);
                    }
                }
            }
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    private String resolveForwardServiceName(Forward forward, String fallbackName) {
        if (forward == null || forward.getId() == null || forward.getUserId() == null || forward.getTunnelId() == null) {
            return fallbackName;
        }
        UserTunnel userTunnel = userTunnelService.getOne(
                new QueryWrapper<UserTunnel>()
                        .eq("user_id", forward.getUserId())
                        .eq("tunnel_id", forward.getTunnelId())
        );
        String userTunnelId = userTunnel == null ? DEFAULT_USER_TUNNEL_ID : userTunnel.getId().toString();
        return buildServiceName(forward.getId().toString(), forward.getUserId().toString(), userTunnelId);
    }

    private FlowDto filterFlowData(FlowDto flowDto, Tunnel tunnel, int flowType) {
        if (tunnel != null) {
            BigDecimal trafficRatio = tunnel.getTrafficRatio() == null ? BigDecimal.ONE : tunnel.getTrafficRatio();

            BigDecimal originalD = BigDecimal.valueOf(flowDto.getD() == null ? 0 : flowDto.getD());
            BigDecimal originalU = BigDecimal.valueOf(flowDto.getU() == null ? 0 : flowDto.getU());

            BigDecimal newD = originalD.multiply(trafficRatio);
            BigDecimal newU = originalU.multiply(trafficRatio);

            if (flowType == 1) {
                flowDto.setD(newD.longValue());
                flowDto.setU(0L);
            } else {
                flowDto.setD(newD.longValue());
                flowDto.setU(newU.longValue());
            }
        }
        return flowDto;
    }

    private int getFlowType(Tunnel tunnel) {
        int defaultFlowType = 2;
        if (tunnel == null) return defaultFlowType;
        return tunnel.getFlow();
    }

    private int updateForwardFlow(String forwardId, FlowDto flowStats) {
        UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", forwardId);
        updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
        updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());
        return forwardService.update(null, updateWrapper) ? 1 : 0;
    }

    private int updateUserFlow(String userId, FlowDto flowStats) {
        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userId);
        updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
        updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());
        return userService.update(null, updateWrapper) ? 1 : 0;
    }

    private void updateUserTunnelFlow(String userTunnelId, FlowDto flowStats) {
        if (Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            return; // 默认隧道不需要更新
        }

        UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userTunnelId);
        updateWrapper.setSql("in_flow = in_flow + " + flowStats.getD());
        updateWrapper.setSql("out_flow = out_flow + " + flowStats.getU());
        requireUpdated("用户隧道流量", userTunnelService.update(null, updateWrapper) ? 1 : 0);
    }

    private void requireUpdated(String counterName, int updateCount) {
        if (updateCount != 1) {
            throw new IllegalStateException(counterName + "更新失败");
        }
    }

    private boolean isLimitCheckDue(String key) {
        long now = System.currentTimeMillis();
        Long previous = LIMIT_CHECK_TIMES.get(key);
        if (previous != null && now - previous < LIMIT_CHECK_INTERVAL_MS) {
            return false;
        }
        LIMIT_CHECK_TIMES.put(key, now);
        return true;
    }

    private boolean isValidNode(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            return false;
        }
        String normalizedSecret = secret.trim();
        long now = System.currentTimeMillis();
        CachedNodeSecret cached = NODE_SECRET_CACHE.get(normalizedSecret);
        if (cached != null && cached.expiresAt > now) {
            return cached.valid;
        }

        int nodeCount = nodeService.count(new QueryWrapper<Node>().eq("secret", normalizedSecret));
        NODE_SECRET_CACHE.put(normalizedSecret, new CachedNodeSecret(nodeCount > 0, now + NODE_SECRET_CACHE_TTL_MS));
        return nodeCount > 0;
    }

    private static class CachedNodeSecret {
        private final boolean valid;
        private final long expiresAt;

        private CachedNodeSecret(boolean valid, long expiresAt) {
            this.valid = valid;
            this.expiresAt = expiresAt;
        }
    }

    private String[] parseServiceName(String serviceName) {
        return serviceName.split("_");
    }

    private String buildServiceName(String forwardId, String userId, String userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }
}
