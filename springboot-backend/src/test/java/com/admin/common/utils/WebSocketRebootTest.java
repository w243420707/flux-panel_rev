package com.admin.common.utils;

import com.admin.common.dto.GostDto;
import com.admin.service.NodeRebootService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketRebootTest {
    private WebSocketServer server;
    private WebSocketSession node;
    private NodeRebootService reboots;

    @BeforeEach
    void setUp() {
        server = new WebSocketServer();
        reboots = mock(NodeRebootService.class);
        ReflectionTestUtils.setField(server, "nodeRebootService", reboots);
        node = session("node-1", 1L, "1");
        sessions().put(1L, node);
    }

    @AfterEach
    void clearState() {
        for (String field : new String[]{"nodeSessions", "sessionLocks", "pendingRequests", "cryptoCache"}) {
            ((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, field)).clear();
        }
    }

    @Test
    void onlyTheTargetNodeSessionCanAcknowledgeAReboot() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(call -> { sent.countDown(); return null; }).when(node).sendMessage(any());
        CompletableFuture<GostDto> result = CompletableFuture.supplyAsync(() ->
                WebSocketServer.send_msg(1L, Map.of(), "RebootNode", 3000, "ack-1"));
        assertTrue(sent.await(1, TimeUnit.SECONDS));
        WebSocketSession otherNode = session("node-2", 2L, "1");
        sessions().put(2L, otherNode);
        server.handleTextMessage(otherNode, ack("ack-1", true));
        server.handleTextMessage(session("admin", 99L, "0"), ack("ack-1", true));
        server.handleTextMessage(session("old-node-1", 1L, "1"), ack("ack-1", true));
        assertFalse(result.isDone(), "foreign, administrator, and replaced sessions must not complete the request");
        server.handleTextMessage(node, ack("ack-1", true));
        assertEquals(0, result.get(1, TimeUnit.SECONDS).getCode());
    }

    @Test
    void negativeAcknowledgementWithOkTextStillFails() throws Exception {
        doAnswer(call -> { server.handleTextMessage(node, ack("reject", false)); return null; })
                .when(node).sendMessage(any());
        GostDto result = WebSocketServer.send_msg(1L, Map.of(), "RebootNode", 500, "reject");
        assertEquals(-1, result.getCode());
    }

    @Test
    void responseTimeoutNeverResendsReboot() throws Exception {
        GostDto result = WebSocketServer.send_msg(1L, Map.of(), "RebootNode", 500, "timeout");
        assertNull(result.getCode());
        assertTrue(result.getMsg().contains("超时"));
        verify(node, times(1)).sendMessage(any());
    }

    @Test
    void aQueuedRebootCannotBeSentToAReplacementConnection() throws Exception {
        GostDto result = WebSocketServer.send_msg(1L, Map.of(), "RebootNode", 500, "stale",
                "previous-connection", () -> true);
        assertEquals(-1, result.getCode());
        verify(node, never()).sendMessage(any());
    }

    @Test
    void anInvalidatedAttemptCannotWriteEvenToTheSameConnection() throws Exception {
        GostDto result = WebSocketServer.send_msg(1L, Map.of(), "RebootNode", 500, "cancelled",
                "node-1", () -> false);
        assertEquals(-1, result.getCode());
        verify(node, never()).sendMessage(any());
    }

    @Test
    void executionFailureIsHandledEvenAfterTheAckHasCompleted() {
        server.handleTextMessage(node, new TextMessage("{\"type\":\"RebootNodeStatus\",\"requestId\":\"finished\","
                + "\"success\":false,\"message\":\"权限不足\",\"data\":{\"status\":\"failed\"}}"));
        verify(reboots).onExecutionFailure(1L, "finished", "权限不足");
    }

    private TextMessage ack(String requestId, boolean success) {
        return new TextMessage("{\"type\":\"RebootNodeResponse\",\"requestId\":\"" + requestId
                + "\",\"success\":" + success + ",\"message\":\"OK\",\"data\":{\"status\":\"accepted\"}}");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, WebSocketSession> sessions() {
        return (Map<Long, WebSocketSession>) ReflectionTestUtils.getField(WebSocketServer.class, "nodeSessions");
    }

    private WebSocketSession session(String name, Long id, String type) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", id);
        attributes.put("type", type);
        when(session.getId()).thenReturn(name);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
