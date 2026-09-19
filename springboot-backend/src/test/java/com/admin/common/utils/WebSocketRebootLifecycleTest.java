package com.admin.common.utils;

import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.admin.service.CloudflareDnsSyncService;
import com.admin.service.NodeRebootService;
import com.admin.service.NodeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketRebootLifecycleTest {
    private static final long NODE_ID = 71L;
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private final Node stored = new Node();
    private NodeMapper mapper;
    private NodeRebootService reboots;
    private WebSocketServer server;

    @BeforeEach
    void setUp() {
        clearStaticState();
        stored.setId(NODE_ID);
        stored.setVersion("3.1.6");
        stored.setStatus(1);
        stored.setRebootIntervalHours(2);
        stored.setRebootNextAt(System.currentTimeMillis() + HOUR);
        mapper = mock(NodeMapper.class);
        when(mapper.selectById(NODE_ID)).thenAnswer(call -> snapshot());
        when(mapper.updateRebootNextAt(eq(NODE_ID), nullable(Long.class))).thenAnswer(call -> {
            synchronized (stored) {
                stored.setRebootNextAt(call.getArgument(1));
            }
            return 1;
        });
        NodeService nodes = mock(NodeService.class);
        when(nodes.getById(NODE_ID)).thenAnswer(call -> snapshot());
        when(nodes.updateById(any(Node.class))).thenAnswer(call -> {
            Node update = call.getArgument(0);
            synchronized (stored) {
                // These are ordinary entity updates: reboot fields have updateStrategy=NEVER.
                stored.setStatus(update.getStatus());
                stored.setVersion(update.getVersion());
            }
            return true;
        });
        reboots = spy(new NodeRebootService(mapper, Runnable::run));
        server = new WebSocketServer();
        server.nodeService = nodes;
        server.nodeRebootService = reboots;
        server.cloudflareDnsSyncService = mock(CloudflareDnsSyncService.class);
    }

    @AfterEach
    void clearStaticState() {
        for (String field : new String[]{"nodeSessions", "sessionLocks", "pendingRequests", "cryptoCache", "latestSystemInfo"}) {
            ((Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, field)).clear();
        }
        ((Set<?>) ReflectionTestUtils.getField(WebSocketServer.class, "activeSessions")).clear();
    }

    @Test
    void closingTheOldConnectionCannotEraseTheNewConnectionsSchedule() throws Exception {
        WebSocketSession oldSession = session("old-connection");
        WebSocketSession newSession = session("new-connection");
        server.afterConnectionEstablished(oldSession);
        CountDownLatch closePaused = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountDownLatch connectStarted = new CountDownLatch(1);
        doAnswer(call -> {
            closePaused.countDown();
            assertTrue(allowClose.await(2, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(reboots).onOffline(NODE_ID);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> closing = pool.submit(() -> server.afterConnectionClosed(oldSession, CloseStatus.NORMAL));
            assertTrue(closePaused.await(2, TimeUnit.SECONDS), "old close must pause after removing its session");
            Future<?> connecting = pool.submit(() -> {
                connectStarted.countDown();
                server.afterConnectionEstablished(newSession);
            });
            assertTrue(connectStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> connecting.get(150, TimeUnit.MILLISECONDS),
                    "new online effects must wait until the preceding offline effects have finished");
            allowClose.countDown();
            closing.get(2, TimeUnit.SECONDS);
            connecting.get(2, TimeUnit.SECONDS);

            Node finalNode = snapshot();
            assertSame(newSession, sessions().get(NODE_ID));
            assertTrue(WebSocketServer.isNodeConnected(NODE_ID));
            assertEquals(1, finalNode.getStatus());
            assertNotNull(finalNode.getRebootNextAt(), "a late close must not discard the next reboot");
            assertTrue(finalNode.getRebootNextAt() >= System.currentTimeMillis() + 2 * HOUR - 5000);
        } finally {
            allowClose.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void panelShutdownAndStartupPreserveAFutureDeadline() {
        WebSocketSession original = session("before-panel-restart");
        server.afterConnectionEstablished(original);
        Long savedDeadline = snapshot().getRebootNextAt();

        reboots.onStopping();
        server.afterConnectionClosed(original, CloseStatus.GOING_AWAY);

        assertFalse(WebSocketServer.isNodeConnected(NODE_ID));
        assertEquals(1, snapshot().getStatus(), "panel shutdown is not a VPS-offline observation");
        assertEquals(savedDeadline, snapshot().getRebootNextAt());
        verify(reboots, never()).onOffline(NODE_ID);

        NodeRebootService restarted = new NodeRebootService(mapper, Runnable::run);
        server.nodeRebootService = restarted;
        WebSocketSession restored = session("after-panel-restart");
        server.afterConnectionEstablished(restored);

        assertSame(restored, sessions().get(NODE_ID));
        assertEquals(1, snapshot().getStatus());
        assertEquals(savedDeadline, snapshot().getRebootNextAt(), "future schedule must survive a panel restart");
    }

    private Node snapshot() {
        synchronized (stored) {
            Node copy = new Node();
            copy.setId(stored.getId());
            copy.setVersion(stored.getVersion());
            copy.setStatus(stored.getStatus());
            copy.setRebootIntervalHours(stored.getRebootIntervalHours());
            copy.setRebootNextAt(stored.getRebootNextAt());
            return copy;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Long, WebSocketSession> sessions() {
        return (Map<Long, WebSocketSession>) ReflectionTestUtils.getField(WebSocketServer.class, "nodeSessions");
    }

    private WebSocketSession session(String name) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", NODE_ID);
        attributes.put("type", "1");
        attributes.put("nodeVersion", "3.1.6");
        when(session.getId()).thenReturn(name);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
