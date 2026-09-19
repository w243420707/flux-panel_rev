package com.admin.common.utils;

import com.admin.common.dto.UsageSnapshot;
import com.admin.entity.Node;
import com.admin.service.CloudflareDnsSyncService;
import com.admin.service.NodeRebootService;
import com.admin.service.NodeService;
import com.admin.service.NodeUsageService;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketVpsUsageTest {
    private static final long NODE_ID = 71L;
    private static final long USAGE_ID = 42L;
    private static final String EPOCH = "20000000-0000-0000-0000-000000000001";
    private static final String NEXT_EPOCH = "20000000-0000-0000-0000-000000000002";
    private final Map<String, List<JSONObject>> messages = new HashMap<>();
    private WebSocketServer server;
    private NodeService nodes;
    private NodeUsageService usages;
    private NodeRebootService reboots;
    private Node stored;

    @BeforeEach
    void setUp() {
        clearState();
        stored = new Node();
        stored.setId(NODE_ID);
        stored.setStatus(0);
        nodes = mock(NodeService.class);
        when(nodes.getById(NODE_ID)).thenReturn(stored);
        when(nodes.updateById(any(Node.class))).thenReturn(true);
        usages = mock(NodeUsageService.class);
        reboots = mock(NodeRebootService.class);
        server = new WebSocketServer();
        server.nodeService = nodes;
        server.nodeUsageService = usages;
        server.nodeRebootService = reboots;
        server.cloudflareDnsSyncService = mock(CloudflareDnsSyncService.class);
    }

    @AfterEach
    void clearState() {
        for (String field : new String[]{"nodeSessions", "usageCandidateSessions", "sessionLocks",
                "pendingRequests", "cryptoCache", "latestSystemInfo"}) {
            ((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, field)).clear();
        }
        ((Set<?>) ReflectionTestUtils.getField(WebSocketServer.class, "activeSessions")).clear();
    }

    @Test
    void legacyNodeWithoutUsageHistoryStillConnectsAndReportsMetrics() throws Exception {
        WebSocketSession legacy = node("legacy", false);
        server.afterConnectionEstablished(legacy);
        server.handleTextMessage(legacy, metrics(null, "192.0.2.1"));

        assertSame(legacy, sessions("nodeSessions").get(NODE_ID));
        assertEquals(1, stored.getStatus());
        assertEquals("3.1.6", stored.getVersion());
        verify(nodes).refreshRuntimeNodeServerIpAsync(NODE_ID, "192.0.2.1", null, null, null);
        verifyNoInteractions(usages);
    }

    @Test
    void unapprovedCandidateCannotReplaceTheLiveNodeOrPublishItsIpAndMetrics() throws Exception {
        WebSocketSession active = node("active", false);
        server.afterConnectionEstablished(active);
        WebSocketSession admin = viewer("admin", true);
        WebSocketSession candidate = node("candidate", true);
        clearInvocations(nodes, reboots);
        when(usages.admit(eq(NODE_ID), eq("candidate"), any(), eq(true), eq("192.0.2.99")))
                .thenReturn(new NodeUsageService.AdmissionDecision(false, null, pending()));

        server.afterConnectionEstablished(candidate);
        assertSame(active, sessions("nodeSessions").get(NODE_ID));
        server.handleTextMessage(candidate, metrics(snapshot(EPOCH, 1), "192.0.2.99"));

        verify(usages).admit(eq(NODE_ID), eq("candidate"), any(), eq(true), eq("192.0.2.99"));
        assertSame(active, sessions("nodeSessions").get(NODE_ID));
        assertSame(candidate, sessions("usageCandidateSessions").get(NODE_ID));
        verify(active, never()).close();
        verify(active, never()).close(any());
        verify(nodes, never()).updateById(any(Node.class));
        verify(nodes, never()).refreshRuntimeNodeServerIpAsync(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(reboots);
        assertTrue(messages.get(admin.getId()).stream().noneMatch(message -> "info".equals(message.getString("type"))));
    }

    @Test
    void admittedCandidateTakesOverAndOldFramesCannotAffectItsUsageOrAddress() throws Exception {
        WebSocketSession previous = node("previous", false);
        server.afterConnectionEstablished(previous);
        WebSocketSession candidate = node("accepted", true);
        when(usages.admit(eq(NODE_ID), eq("accepted"), any(), eq(true), any()))
                .thenReturn(new NodeUsageService.AdmissionDecision(true, USAGE_ID, active()));
        server.afterConnectionEstablished(candidate);
        server.handleTextMessage(candidate, metrics(snapshot(EPOCH, 1), "192.0.2.2"));

        assertSame(candidate, sessions("nodeSessions").get(NODE_ID));
        assertFalse(sessions("usageCandidateSessions").containsKey(NODE_ID));
        assertEquals(USAGE_ID, candidate.getAttributes().get("usageId"));
        assertEquals(EPOCH, ((UsageSnapshot) candidate.getAttributes().get("usageSnapshot")).meterEpoch());
        verify(previous).close();
        clearInvocations(nodes, usages);
        when(usages.acceptSample(eq(NODE_ID), eq(USAGE_ID), any())).thenReturn(active());

        server.handleTextMessage(previous, metrics(snapshot(EPOCH, 999), "192.0.2.250"));
        server.handleTextMessage(candidate, metrics(snapshot(EPOCH, 2), "192.0.2.3"));

        verify(usages).acceptSample(eq(NODE_ID), eq(USAGE_ID), argThat(sample -> sample.sequence() == 2));
        verify(usages, never()).admit(anyLong(), any(), any(), anyBoolean(), any());
        verify(nodes, never()).refreshRuntimeNodeServerIpAsync(eq(NODE_ID), eq("192.0.2.250"), any(), any(), any());
        verify(nodes).refreshRuntimeNodeServerIpAsync(NODE_ID, "192.0.2.3", null, null, null);
    }

    @Test
    void candidateDisconnectClearsOnlyItsPendingStateAndKeepsTheOriginalOnline() throws Exception {
        WebSocketSession original = node("original", false);
        server.afterConnectionEstablished(original);
        WebSocketSession candidate = node("leaving-candidate", true);
        when(usages.admit(eq(NODE_ID), any(), any(), eq(true), any()))
                .thenReturn(new NodeUsageService.AdmissionDecision(false, null, pending()));
        when(usages.summary(NODE_ID)).thenReturn(active());
        server.afterConnectionEstablished(candidate);
        server.handleTextMessage(candidate, metrics(snapshot(EPOCH, 1), "192.0.2.2"));
        clearInvocations(nodes, reboots);

        server.afterConnectionClosed(candidate, CloseStatus.NORMAL);

        assertSame(original, sessions("nodeSessions").get(NODE_ID));
        assertTrue(WebSocketServer.isNodeConnected(NODE_ID));
        assertEquals(1, stored.getStatus());
        assertFalse(sessions("usageCandidateSessions").containsKey(NODE_ID));
        verify(usages).discardCandidate(NODE_ID, "leaving-candidate");
        verify(nodes, never()).updateById(any(Node.class));
        verify(reboots, never()).onOffline(NODE_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "epoch", "negative", "fractional", "incomplete"})
    void missingOrInvalidSnapshotCannotAdmitANewConnection(String problem) throws Exception {
        WebSocketSession candidate = node("invalid", true);
        server.afterConnectionEstablished(candidate);
        JSONObject sample = snapshot(EPOCH, 1);
        switch (problem) {
            case "missing" -> sample = null;
            case "epoch" -> sample.put("meter_epoch", "not-an-epoch");
            case "negative" -> sample.put("upload_bytes", -1);
            case "fractional" -> sample.put("sequence", 1.5);
            case "incomplete" -> sample.remove("download_bytes");
        }

        server.handleTextMessage(candidate, metrics(sample, "192.0.2.99"));

        assertFalse(WebSocketServer.isNodeConnected(NODE_ID));
        assertSame(candidate, sessions("usageCandidateSessions").get(NODE_ID));
        verifyNoInteractions(usages);
        verify(nodes, never()).updateById(any(Node.class));
        verify(nodes, never()).refreshRuntimeNodeServerIpAsync(anyLong(), any(), any(), any(), any());
    }

    @Test
    void changedMeterEpochMustReconnectAndBeAdmittedBeforeAnotherSampleIsCounted() throws Exception {
        WebSocketSession first = node("epoch-one", true);
        when(usages.admit(eq(NODE_ID), any(), any(), anyBoolean(), any()))
                .thenReturn(new NodeUsageService.AdmissionDecision(true, USAGE_ID, active()));
        server.afterConnectionEstablished(first);
        server.handleTextMessage(first, metrics(snapshot(EPOCH, 1), "192.0.2.1"));
        clearInvocations(nodes, usages);

        server.handleTextMessage(first, metrics(snapshot(NEXT_EPOCH, 1), "192.0.2.99"));
        server.handleTextMessage(first, metrics(snapshot(EPOCH, 2), "192.0.2.99"));

        verify(first).close(argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        verifyNoInteractions(usages);
        verify(nodes, never()).refreshRuntimeNodeServerIpAsync(anyLong(), any(), any(), any(), any());

        WebSocketSession next = node("epoch-two", true);
        server.afterConnectionEstablished(next);
        server.handleTextMessage(next, metrics(snapshot(NEXT_EPOCH, 1), "192.0.2.2"));
        verify(usages).admit(eq(NODE_ID), eq("epoch-two"), argThat(sample -> NEXT_EPOCH.equals(sample.meterEpoch())), eq(false), any());
        when(usages.acceptSample(eq(NODE_ID), eq(USAGE_ID), any())).thenReturn(active());
        server.handleTextMessage(next, metrics(snapshot(NEXT_EPOCH, 2), "192.0.2.2"));
        verify(usages).acceptSample(eq(NODE_ID), eq(USAGE_ID), argThat(sample -> NEXT_EPOCH.equals(sample.meterEpoch()) && sample.sequence() == 2));
    }

    @Test
    void usageBroadcastContainsRecordFieldsAndExplicitNullsOnlyForAdministrators() throws Exception {
        WebSocketSession admin = viewer("administrator", true);
        WebSocketSession ordinary = viewer("ordinary", false);
        WebSocketServer.broadcastUsage(NODE_ID, active());
        WebSocketServer.broadcastUsage(NODE_ID, new NodeUsageService.UsageSummary("unsupported", null, null));

        List<JSONObject> delivered = messages.get(admin.getId());
        assertEquals(2, delivered.size());
        JSONObject usage = delivered.get(0).getJSONObject("data");
        assertEquals("usage", delivered.get(0).getString("type"));
        assertEquals(NODE_ID, delivered.get(0).getLongValue("id"));
        assertEquals(USAGE_ID, usage.getJSONObject("current").getLongValue("id"));
        assertEquals(300L, usage.getJSONObject("current").getLongValue("totalBytes"));
        assertTrue(usage.getJSONObject("current").containsKey("replacedAt"));
        assertNull(usage.getJSONObject("current").get("replacedAt"));
        assertTrue(usage.containsKey("pending"));
        assertNull(usage.get("pending"));
        JSONObject unsupported = delivered.get(1).getJSONObject("data");
        assertTrue(unsupported.containsKey("current"));
        assertNull(unsupported.get("current"));
        assertTrue(unsupported.containsKey("pending"));
        assertNull(unsupported.get("pending"));
        assertTrue(messages.get(ordinary.getId()).isEmpty());
    }

    private NodeUsageService.UsageSummary active() {
        return new NodeUsageService.UsageSummary("active", new NodeUsageService.UsageRecord(USAGE_ID,
                1000L, null, 100L, 200L, 300L, 60L, 61000L, "192.0.2.1"), null);
    }

    private NodeUsageService.UsageSummary pending() {
        return new NodeUsageService.UsageSummary("pending", active().current(),
                new NodeUsageService.Candidate("candidate-id", 62000L, "192.0.2.99", "请确认 VPS", true));
    }

    private JSONObject snapshot(String epoch, long sequence) {
        JSONObject value = new JSONObject();
        value.put("hardware_id", "a".repeat(64));
        value.put("system_id", "b".repeat(64));
        value.put("installation_id", "10000000-0000-0000-0000-000000000001");
        value.put("meter_epoch", epoch);
        value.put("sequence", sequence);
        value.put("upload_bytes", sequence * 100);
        value.put("download_bytes", sequence * 200);
        return value;
    }

    private TextMessage metrics(JSONObject snapshot, String ip) {
        JSONObject metrics = new JSONObject();
        metrics.put("memory_usage", 20);
        metrics.put("uptime", 10);
        metrics.put("bytes_transmitted", 123);
        metrics.put("public_ip", ip);
        if (snapshot != null) metrics.put("vps_usage", snapshot);
        return new TextMessage(metrics.toJSONString());
    }

    private WebSocketSession node(String name, boolean capable) throws Exception {
        WebSocketSession session = session(name, "1");
        session.getAttributes().put("nodeVersion", capable ? "3.1.7" : "3.1.6");
        session.getAttributes().put("usageCapable", capable);
        return session;
    }

    private WebSocketSession viewer(String name, boolean administrator) throws Exception {
        WebSocketSession session = session(name, "0");
        session.getAttributes().put("administrator", administrator);
        server.afterConnectionEstablished(session);
        messages.get(name).clear();
        return session;
    }

    private WebSocketSession session(String name, String type) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", NODE_ID);
        attributes.put("type", type);
        AtomicBoolean open = new AtomicBoolean(true);
        messages.put(name, new ArrayList<>());
        when(session.getId()).thenReturn(name);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenAnswer(call -> open.get());
        doAnswer(call -> { open.set(false); return null; }).when(session).close();
        doAnswer(call -> { open.set(false); return null; }).when(session).close(any(CloseStatus.class));
        doAnswer(call -> {
            TextMessage message = call.getArgument(0);
            messages.get(name).add(JSONObject.parseObject(message.getPayload()));
            return null;
        }).when(session).sendMessage(any());
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, WebSocketSession> sessions(String field) {
        return (Map<Long, WebSocketSession>) ReflectionTestUtils.getField(WebSocketServer.class, field);
    }
}
