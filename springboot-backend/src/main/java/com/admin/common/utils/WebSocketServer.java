package com.admin.common.utils;


import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.common.task.CheckGostConfigAsync;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;


@Slf4j
public class WebSocketServer extends TextWebSocketHandler {

    @Resource
    NodeService nodeService;

    // 存储所有活跃的 WebSocket 连接（
    private static final CopyOnWriteArraySet<WebSocketSession> activeSessions = new CopyOnWriteArraySet<>();
    
    // 存储节点ID和对应的WebSocket session映射
    private static final ConcurrentHashMap<Long, WebSocketSession> nodeSessions = new ConcurrentHashMap<>();

    // 保存每个节点最近一次系统指标，管理员页面重新连接时立即回放，避免等待下一次心跳。
    private static final ConcurrentHashMap<Long, String> latestSystemInfo = new ConcurrentHashMap<>();
    
    // 为每个session提供锁对象，防止并发发送消息
    private static final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    
    // 存储等待响应的请求，key为requestId，value为CompletableFuture
    private static final ConcurrentHashMap<String, CompletableFuture<GostDto>> pendingRequests = new ConcurrentHashMap<>();
    
    // 缓存加密器实例，避免重复创建
    private static final ConcurrentHashMap<String, AESCrypto> cryptoCache = new ConcurrentHashMap<>();

    /**
     * 加密消息包装器
     */
    public static class EncryptedMessage {
        private boolean encrypted;
        private String data;
        private Long timestamp;

        // getters and setters
        public boolean isEncrypted() { return encrypted; }
        public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    }

    //接受客户端消息
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            if (StringUtils.isNoneBlank(message.getPayload())) {
                
                String id = session.getAttributes().get("id").toString();
                String type = session.getAttributes().get("type").toString();
                String nodeSecret = (String) session.getAttributes().get("nodeSecret");

                // 尝试解密消息
                String decryptedPayload = decryptMessageIfNeeded(message.getPayload(), nodeSecret);

                if (decryptedPayload.contains("memory_usage")){
                    if (Objects.equals(type, "1")) {
                        handleNodeSystemInfo(id, session, decryptedPayload);
                    }
                    sendToUser(session, "{\"type\":\"call\"}", nodeSecret);
                    // 先发送确认消息
                    sendToUser(session, "{\"type\":\"call\"}", nodeSecret);
                }else if (decryptedPayload.contains("requestId")) {
                    // 处理命令响应消息
                    try {
                        JSONObject responseJson = JSONObject.parseObject(decryptedPayload);
                        String requestId = responseJson.getString("requestId");
                        String responseMessage = responseJson.getString("message");
                        String responseType = responseJson.getString("type");
                        boolean responseSuccess = responseJson.getBooleanValue("success");
                        JSONObject responseData = responseJson.getJSONObject("data");

                        if (!responseSuccess || !"OK".equals(responseMessage)) {
                            log.warn("节点命令执行失败 [nodeId={}, type={}, requestId={}, responseType={}]: {}",
                                    id, responseType, requestId, responseType, responseMessage);
                        } else {
                            log.debug("收到节点响应 [nodeId={}, type={}, requestId={}, success={}, message={}]",
                                    id, responseType, requestId, responseSuccess, responseMessage);
                        }
                        
                        if (requestId != null) {
                            CompletableFuture<GostDto> future = pendingRequests.remove(requestId);

                            if (future != null) {
                                GostDto result = new GostDto();
                                
                                // 根据响应类型处理不同的数据
                                if (("PingResponse".equals(responseType) || "TcpPingResponse".equals(responseType) || "UdpPingResponse".equals(responseType)) && responseData != null) {
                                    // 特殊处理ping响应，将完整的响应数据返回
                                    result.setMsg(responseMessage != null ? responseMessage : "OK");
                                    result.setData(responseData); // 保存ping详细结果
                                } else {
                                    // 其他类型的响应
                                    result.setMsg(responseMessage != null ? responseMessage : "无响应消息");
                                    if (responseData != null) {
                                        result.setData(responseData);
                                    }
                                }
                                
                                future.complete(result);
                            }
                        }
                    } catch (Exception e) {
                        log.info("处理响应消息失败: {}", e.getMessage(), e);
                    }
                } else {
                    log.debug("收到节点消息 [nodeId={}]: {}", id, decryptedPayload);
                }

                // 如果是节点类型，转发消息给其他会话
                if (decryptedPayload.contains("requestId")) {
                    return;
                }

                if (Objects.equals(type, "1")) {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("id", id);
                    jsonObject.put("type", "info");
                    jsonObject.put("data", decryptedPayload);
                    String broadcastMessage = jsonObject.toJSONString();
                    
                    // 异步处理广播消息，避免阻塞当前线程
                    for (WebSocketSession targetSession : activeSessions) {
                        if (targetSession != null && targetSession.isOpen() && !targetSession.equals(session)) {
                            sendToUser(targetSession, broadcastMessage, null);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("处理WebSocket消息时发生异常: {}", e.getMessage(), e);
        }
    }

    private void handleNodeSystemInfo(String id, WebSocketSession session, String payload) {
        try {
            JSONObject info = JSONObject.parseObject(payload);
            Long nodeId = Long.valueOf(id);
            cacheLatestSystemInfo(nodeId, info);
            String publicIp = firstNonBlank(info.getString("public_ip"), info.getString("publicIp"), info.getString("host_ip"));
            String publicIpv4 = firstNonBlank(info.getString("public_ipv4"), info.getString("publicIpv4"));
            String publicIpv6 = firstNonBlank(info.getString("public_ipv6"), info.getString("publicIpv6"));
            String clientIp = (String) session.getAttributes().get("clientIp");
            if (StringUtils.isBlank(publicIp)
                    && StringUtils.isBlank(publicIpv4)
                    && StringUtils.isBlank(publicIpv6)
                    && StringUtils.isBlank(clientIp)) {
                return;
            }
            String effectivePublicIp = firstNonBlank(publicIp, publicIpv4, publicIpv6, clientIp);
            if (!runtimeIpsChanged(session, effectivePublicIp, publicIpv4, publicIpv6)) {
                return;
            }

            nodeService.refreshRuntimeNodeServerIp(nodeId, effectivePublicIp, publicIpv4, publicIpv6, clientIp);
            rememberRuntimeIps(session, effectivePublicIp, publicIpv4, publicIpv6);
        } catch (Exception e) {
            log.info("刷新节点公网IP失败: {}", e.getMessage());
        }
    }

    private boolean runtimeIpsChanged(WebSocketSession session, String publicIp, String publicIpv4, String publicIpv6) {
        return !Objects.equals(normalizeRuntimeIp(publicIp), session.getAttributes().get("lastPublicIp"))
                || !Objects.equals(normalizeRuntimeIp(publicIpv4), session.getAttributes().get("lastPublicIpv4"))
                || !Objects.equals(normalizeRuntimeIp(publicIpv6), session.getAttributes().get("lastPublicIpv6"));
    }

    private void rememberRuntimeIps(WebSocketSession session, String publicIp, String publicIpv4, String publicIpv6) {
        rememberRuntimeIp(session, "lastPublicIp", publicIp);
        rememberRuntimeIp(session, "lastPublicIpv4", publicIpv4);
        rememberRuntimeIp(session, "lastPublicIpv6", publicIpv6);
    }

    private void rememberRuntimeIp(WebSocketSession session, String key, String value) {
        String normalized = normalizeRuntimeIp(value);
        if (normalized == null) {
            session.getAttributes().remove(key);
        } else {
            session.getAttributes().put(key, normalized);
        }
    }

    private String normalizeRuntimeIp(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 缓存最近一次系统指标。公网 IP 不放入回放缓存，避免页面重连时用旧心跳覆盖刚更新的 IP。
     */
    private void cacheLatestSystemInfo(Long nodeId, JSONObject info) {
        if (nodeId == null || info == null) {
            return;
        }
        JSONObject metrics = JSONObject.parseObject(info.toJSONString());
        metrics.remove("public_ip");
        metrics.remove("publicIp");
        metrics.remove("host_ip");
        metrics.remove("public_ipv4");
        metrics.remove("publicIpv4");
        metrics.remove("public_ipv6");
        metrics.remove("publicIpv6");
        latestSystemInfo.put(nodeId, metrics.toJSONString());
    }

    /**
     * 管理员 WebSocket 建立后立即补发最近指标，避免页面必须等待下一条节点心跳。
     */
    private void sendLatestSystemInfo(WebSocketSession session) {
        latestSystemInfo.forEach((nodeId, payload) -> {
            JSONObject message = new JSONObject();
            message.put("id", nodeId);
            message.put("type", "info");
            message.put("data", payload);
            sendToUser(session, message.toJSONString());
        });
    }

    /**
     * 删除节点时同步清理内存中的最近指标。
     */
    public static void clearLatestSystemInfo(Long nodeId) {
        if (nodeId != null) {
            latestSystemInfo.remove(nodeId);
        }
    }

    /**
     * 尝试解密消息（如果需要）
     */
    private String decryptMessageIfNeeded(String payload, String nodeSecret) {
        if (payload == null || payload.trim().isEmpty()) {
            return payload;
        }

        try {
            // 尝试解析为加密消息格式
            EncryptedMessage encryptedMessage = JSON.parseObject(payload, EncryptedMessage.class);
            
            if (encryptedMessage.isEncrypted() && encryptedMessage.getData() != null) {
                // 获取或创建加密器
                AESCrypto crypto = getOrCreateCrypto(nodeSecret);
                if (crypto == null) {
                    log.info("⚠️ 收到加密消息但无法创建解密器，使用原始数据");
                    return payload;
                }
                
                // 解密数据
                String decryptedData = crypto.decryptString(encryptedMessage.getData());
                return decryptedData;
            }
        } catch (Exception e) {
            // 解析失败，可能是非加密格式，直接返回原始数据
            log.info("WebSocket消息未加密或解密失败，使用原始数据: {}", e.getMessage());
        }
        
        return payload;
    }

    /**
     * 加密消息（如果可能）
     */
    private static String encryptMessageIfPossible(String message, String nodeSecret) {
        if (message == null || nodeSecret == null) {
            return message;
        }

        try {
            AESCrypto crypto = getOrCreateCrypto(nodeSecret);
            if (crypto != null) {
                String encryptedData = crypto.encrypt(message);
                
                // 创建加密消息包装器
                JSONObject encryptedMessage = new JSONObject();
                encryptedMessage.put("encrypted", true);
                encryptedMessage.put("data", encryptedData);
                encryptedMessage.put("timestamp", System.currentTimeMillis());
                
                return encryptedMessage.toJSONString();
            }
        } catch (Exception e) {
            log.info("⚠️ WebSocket消息加密失败，发送原始数据: {}", e.getMessage());
        }

        return message;
    }

    /**
     * 获取或创建加密器实例
     */
    private static AESCrypto getOrCreateCrypto(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return cryptoCache.computeIfAbsent(secret, AESCrypto::create);
    }

    // 建立连接
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String id = session.getAttributes().get("id").toString();
            String type = session.getAttributes().get("type").toString();
            
            if (!Objects.equals(type, "1")) {
                // 网页管理员连接
                activeSessions.add(session);
                log.info("管理员连接建立，sessionId: {}", session.getId());
                sendLatestSystemInfo(session);
            } else {
                // 客户端节点连接
                Long nodeId = Long.valueOf(id);
                String version = (String) session.getAttributes().get("nodeVersion");
                String clientIp = (String) session.getAttributes().get("clientIp");
                String nodePublicIp = (String) session.getAttributes().get("nodePublicIp");
                String nodePublicIpv4 = (String) session.getAttributes().get("nodePublicIpv4");
                String nodePublicIpv6 = (String) session.getAttributes().get("nodePublicIpv6");
                
                log.info("节点 {} 尝试连接，开始处理连接逻辑", nodeId);
                
                // 检查是否已有该节点的连接，如果有则记录日志但直接覆盖
                WebSocketSession existingSession = nodeSessions.get(nodeId);
                if (existingSession != null && existingSession.isOpen()) {
                    log.info("节点 {} 已有连接存在: {}，新连接将覆盖旧连接", nodeId, existingSession.getId());
                    // 清理旧连接的锁对象
                    sessionLocks.remove(existingSession.getId());
                }
                
                // 直接覆盖会话映射（不主动关闭旧连接，让它自然断开）
                nodeSessions.put(nodeId, session);
                
                // 如果有旧连接，在覆盖映射后主动关闭它
                if (existingSession != null && existingSession.isOpen()) {
                    try {
                        log.info("主动关闭节点 {} 的旧连接: {}", nodeId, existingSession.getId());
                        existingSession.close();
                    } catch (Exception e) {
                        log.info("关闭节点 {} 旧连接失败: {}", nodeId, e.getMessage());
                    }
                }
                
                // 更新节点状态为在线
                Node node = nodeService.getById(nodeId);
                if (node != null) {
                    // 更新状态和版本信息
                    if (nodePublicIp != null || nodePublicIpv4 != null || nodePublicIpv6 != null || clientIp != null) {
                        nodeService.refreshRuntimeNodeServerIp(nodeId, nodePublicIp, nodePublicIpv4, nodePublicIpv6, clientIp);
                        rememberRuntimeIps(session, nodePublicIp, nodePublicIpv4, nodePublicIpv6);
                        node = nodeService.getById(nodeId);
                    }
                    node.setStatus(1);
                    if (version != null) {
                        node.setVersion(version);
                    }
                    boolean updateResult = nodeService.updateById(node);
                    
                    if (updateResult) {
                        log.info("节点 {} 连接建立成功，状态更新为在线，版本: {}", nodeId, version);
                        
                        // 广播节点上线状态给所有管理员
                        JSONObject res = new JSONObject();
                        res.put("id", id);
                        res.put("type", "status");
                        res.put("data", 1);
                        broadcastMessage(res.toJSONString());
                    } else {
                        log.info("节点 {} 状态更新失败", nodeId);
                    }
                } else {
                    log.info("节点 {} 不存在，无法更新状态", nodeId);
                    // 移除无效的会话
                    nodeSessions.remove(nodeId);
                }
            }

        } catch (Exception e) {
            log.info("建立连接时发生异常: {}", e.getMessage(), e);
            // 异常情况下，确保清理会话
            try {
                String id = session.getAttributes().get("id").toString();
                String type = session.getAttributes().get("type").toString();
                if (Objects.equals(type, "1")) {
                    Long nodeId = Long.valueOf(id);
                    nodeSessions.remove(nodeId);
                    log.info("由于异常，移除节点 {} 的会话", nodeId);
                }
            } catch (Exception cleanupException) {
                log.info("清理异常会话时出错: {}", cleanupException.getMessage());
            }
        }
    }

    // 连接关闭后
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        try {
            String id = session.getAttributes().get("id").toString();
            String type = session.getAttributes().get("type").toString();
            String sessionId = session.getId();
            
            log.info("连接关闭，ID: {}, 类型: {}, 状态: {}", id, type, status);
            
            if (!Objects.equals(type, "1")) {
                // 管理员连接关闭
                boolean removed = activeSessions.remove(session);
                log.info("管理员连接关闭，sessionId: {}, 移除结果: {}", sessionId, removed);
            } else {
                // 客户端节点连接关闭
                Long nodeId = Long.valueOf(id);
                
                // 验证当前会话是否还是活跃会话（关键：这里会自动过滤掉被覆盖的旧连接）
                WebSocketSession currentSession = nodeSessions.get(nodeId);
                if (currentSession == null || !currentSession.equals(session)) {
                    log.info("节点 {} 连接关闭，但已有新连接或会话不匹配，跳过状态更新", nodeId);
                    sessionLocks.remove(sessionId);
                    return;
                }
                
                log.info("节点 {} 当前活跃连接关闭，开始验证并更新状态", nodeId);
                
                    nodeSessions.remove(nodeId);
                    
                    // 更新节点状态为离线
                    Node node = nodeService.getById(nodeId);
                    if (node != null) {
                        node.setStatus(0);
                        boolean updateResult = nodeService.updateById(node);
                        
                        if (updateResult) {
                            log.info("节点 {} 状态更新为离线成功", nodeId);
                            
                            JSONObject res = new JSONObject();
                            res.put("id", id);
                            res.put("type", "status");
                            res.put("data", 0);
                            broadcastMessage(res.toJSONString());
                        } else {
                            log.info("节点 {} 状态更新为离线失败", nodeId);
                        }
                    } else {
                        log.info("节点 {} 不存在，无法更新离线状态", nodeId);
                    }
            }
            
            // 清理session锁对象
            sessionLocks.remove(sessionId);

        } catch (Exception e) {
            log.info("关闭连接时发生异常: {}", e.getMessage(), e);
        }
    }

    // 点对点发送消息
    public static boolean sendToUser(WebSocketSession socketSession, String message) {
        return sendToUser(socketSession, message, null);
    }

    // 点对点发送消息（支持加密）
    public static boolean sendToUser(WebSocketSession socketSession, String message, String nodeSecret) {
        if (socketSession != null && socketSession.isOpen()) {
            String sessionId = socketSession.getId();
            Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
            
            synchronized (lock) {
                try {
                    if (socketSession.isOpen()) {
                        // 如果是节点连接且有密钥，尝试加密消息
                        String finalMessage = message;
                        if (nodeSecret != null && !nodeSecret.isEmpty()) {
                            String type = (String) socketSession.getAttributes().get("type");
                            if ("1".equals(type)) { // 节点连接
                                finalMessage = encryptMessageIfPossible(message, nodeSecret);
                            }
                        }
                        socketSession.sendMessage(new TextMessage(finalMessage));
                        return true;
                    }
                } catch (Exception e) {
                    log.warn("发送WebSocket消息失败 [sessionId={}]: {}", sessionId, e.getMessage());
                    cleanupSession(socketSession);
                    return false;
                }
            }
        } else {
            cleanupSession(socketSession);
        }
        return false;
    }
    
    /**
     * 清理失效的session，自动识别是节点session还是管理员session
     */
    private static void cleanupSession(WebSocketSession session) {
        if (session == null) return;
        
        String sessionId = session.getId();
        
        // 清理session锁
        sessionLocks.remove(sessionId);
        
        boolean removedFromAdmin = activeSessions.remove(session);
        
        if (!removedFromAdmin) {
            nodeSessions.entrySet().removeIf(entry -> {
                if (entry.getValue() == session) {
                    return true;
                }
                return false;
            });
        }
    }

    // 广播消息
    public static void broadcastMessage(String message) {
        for (WebSocketSession session : activeSessions) {
            sendToUser(session, message);
        }
    }



    public static GostDto send_msg(Long node_id, Object msg, String type) {
        WebSocketSession nodeSession = nodeSessions.get(node_id);

        if (nodeSession == null) {
            log.warn("发送节点命令失败 [nodeId={}, type={}]: 节点不在线或会话不存在", node_id, type);
            GostDto result = new GostDto();
            result.setMsg("节点不在线");
            return result;
        }

        if (!nodeSession.isOpen()) {
            log.warn("发送节点命令失败 [nodeId={}, type={}]: 连接已断开，清理会话", node_id, type);
            nodeSessions.remove(node_id);
            sessionLocks.remove(nodeSession.getId());
            GostDto result = new GostDto();
            result.setMsg("节点连接已断开");
            return result;
        }

        // 生成唯一的请求ID
        String requestId = UUID.randomUUID().toString();
        
        // 创建CompletableFuture用于等待响应
        CompletableFuture<GostDto> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        long startedAt = System.nanoTime();
        
        // 获取节点密钥用于加密
        String nodeSecret = (String) nodeSession.getAttributes().get("nodeSecret");

        try {
            JSONObject data = new JSONObject();
            data.put("type", type);
            data.put("data", msg);
            data.put("requestId", requestId);
            if (!sendToUser(nodeSession, data.toJSONString(), nodeSecret)) {
                pendingRequests.remove(requestId);
                GostDto result = new GostDto();
                result.setMsg("发送命令失败：WebSocket 写入失败");
                log.error("发送节点命令失败 [nodeId={}, type={}, requestId={}, sessionId={}]: WebSocket 写入失败",
                        node_id, type, requestId, nodeSession.getId());
                return result;
            }
            try {
                GostDto result = future.get(10, TimeUnit.SECONDS);
                log.debug("节点命令完成 [nodeId={}, type={}, requestId={}, elapsedMs={}, message={}]",
                        node_id, type, requestId, elapsedMillis(startedAt), result.getMsg());
                return result;
            } catch (java.util.concurrent.TimeoutException firstTimeout) {
                log.warn("节点命令首次等待响应超时 [nodeId={}, type={}, requestId={}，自动再等 5 秒]",
                        node_id, type, requestId);
                try {
                    GostDto result = future.get(5, TimeUnit.SECONDS);
                    log.debug("节点命令在延迟等待后完成 [nodeId={}, type={}, requestId={}, elapsedMs={}, message={}]",
                            node_id, type, requestId, elapsedMillis(startedAt), result.getMsg());
                    return result;
                } catch (java.util.concurrent.TimeoutException secondTimeout) {
                    pendingRequests.remove(requestId);
                    GostDto result = new GostDto();
                    result.setMsg("等待响应超时（已自动再等一次）");
                    log.error("节点命令两次等待均超时 [nodeId={}, type={}, requestId={}, elapsedMs={}]: 可能存在连接问题",
                            node_id, type, requestId, elapsedMillis(startedAt));
                    return result;
                }
            }
        } catch (Exception e) {
            pendingRequests.remove(requestId);

            GostDto result = new GostDto();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                result.setMsg("发送消息失败: 线程被中断");
            } else if (e instanceof java.util.concurrent.TimeoutException) {
                result.setMsg("等待响应超时");
                log.error("节点命令响应超时 [nodeId={}, type={}, requestId={}, elapsedMs={}]",
                        node_id, type, requestId, elapsedMillis(startedAt));
            } else {
                result.setMsg("发送消息失败: " + e.getMessage());
                log.error("发送节点命令失败 [nodeId={}, type={}, requestId={}]: {}",
                        node_id, type, requestId, e.getMessage(), e);
            }
            return result;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    
}
