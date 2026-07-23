package com.admin.config;


import com.admin.common.utils.JwtUtil;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import javax.annotation.Resource;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;


@Configuration
@Slf4j
public class WebSocketInterceptor extends HttpSessionHandshakeInterceptor {

    @Resource
    NodeService nodeService;

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception ex) {

    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        ServletServerHttpRequest serverHttpRequest = (ServletServerHttpRequest) request;
        String secret = serverHttpRequest.getServletRequest().getParameter("secret");
        String type = serverHttpRequest.getServletRequest().getParameter("type");
        String version = serverHttpRequest.getServletRequest().getParameter("version");
        String publicIp = serverHttpRequest.getServletRequest().getParameter("publicIp");
        String clientIp = getClientIp(request);
        if (Objects.equals(type, "1")) {
            log.info("节点握手请求，type: {}, version: {}, ip: {}", type, version, clientIp);
            Node node = nodeService.getOne(new QueryWrapper<Node>().eq("secret", secret));
            if (node == null) {
                log.info("节点验证失败：未找到匹配的secret");
                return false;
            }
            attributes.put("id", node.getId());
            attributes.put("nodeSecret", secret);
            attributes.put("nodeVersion", version);
            attributes.put("clientIp", clientIp);
            attributes.put("nodePublicIp", publicIp);
            log.info("节点 {} 通过验证，版本: {}", node.getId(), version);
            // 不在这里更新状态，等到连接建立后再统一更新
        }else {
            boolean b = JwtUtil.validateToken(secret);
            if (!b) return false;
            attributes.put("id", JwtUtil.getUserIdFromToken(secret));
        }
        attributes.put("type", type);
        return true;
    }

    public String getClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String first = forwardedFor.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }

        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return null;
    }


}
