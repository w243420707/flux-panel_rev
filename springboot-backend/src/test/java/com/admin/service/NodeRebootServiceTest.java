package com.admin.service;

import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeRebootServiceTest {
    private static final long NODE_ID = 7L;
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-20T08:00:00Z").toEpochMilli());
    private final AtomicInteger jitter = new AtomicInteger();
    private Node stored;
    private NodeMapper mapper;
    private NodeRebootService service;

    @BeforeEach
    void setUp() {
        stored = new Node();
        stored.setId(NODE_ID);
        stored.setVersion("3.1.6");
        stored.setRebootIntervalHours(0);
        mapper = mock(NodeMapper.class);
        when(mapper.selectById(anyLong())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            return id == NODE_ID ? snapshot() : null;
        });
        when(mapper.updateRebootSchedule(eq(NODE_ID), anyInt(), nullable(Long.class))).thenAnswer(call -> {
            synchronized (stored) {
                stored.setRebootIntervalHours(call.getArgument(1));
                stored.setRebootNextAt(call.getArgument(2));
            }
            return 1;
        });
        when(mapper.updateRebootNextAt(eq(NODE_ID), nullable(Long.class))).thenAnswer(call -> {
            synchronized (stored) {
                stored.setRebootNextAt(call.getArgument(1));
            }
            return 1;
        });
        when(mapper.claimDueReboot(eq(NODE_ID), anyLong(), anyLong())).thenAnswer(call -> {
            synchronized (stored) {
                Long expected = call.getArgument(1);
                long now = call.getArgument(2);
                if (stored.getRebootIntervalHours() <= 0 || !expected.equals(stored.getRebootNextAt())
                        || expected > now) return 0;
                stored.setRebootNextAt(null);
                return 1;
            }
        });
        when(mapper.findDueReboots(anyLong())).thenAnswer(call -> {
            Node node = snapshot();
            long now = call.getArgument(0);
            return node.getRebootIntervalHours() > 0 && node.getRebootNextAt() != null
                    && node.getRebootNextAt() <= now ? List.of(node) : List.of();
        });
        service = createService(Runnable::run);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3599})
    void savedScheduleStartsNowWithTheFullJitterRange(int seconds) {
        jitter.set(seconds);
        R result = service.saveSchedule(NODE_ID, 4);

        assertEquals(0, result.getCode());
        assertEquals(4, stored.getRebootIntervalHours());
        assertEquals(clock.millis() + 4 * HOUR + seconds * 1000L, stored.getRebootNextAt());
        verify(service, never()).sendReboot(anyLong(), anyString());
    }

    @Test
    void zeroDisablesEvenAnOldOfflineNodeAndCancelsItsDeadline() {
        setSchedule(4, clock.millis() + HOUR);
        stored.setVersion("3.1.5");
        doReturn(false).when(service).isOnline(NODE_ID);

        assertEquals(0, service.saveSchedule(NODE_ID, 0).getCode());
        assertEquals(0, stored.getRebootIntervalHours());
        assertNull(stored.getRebootNextAt());
        verify(service, never()).sendReboot(anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 721})
    void invalidHoursDoNotChangeTheSavedSchedule(int hours) {
        setSchedule(2, clock.millis() + 2 * HOUR);
        long deadline = stored.getRebootNextAt();

        assertEquals(-1, service.saveSchedule(NODE_ID, hours).getCode());
        assertEquals(2, stored.getRebootIntervalHours());
        assertEquals(deadline, stored.getRebootNextAt());
    }

    @Test
    void savingWhileOfflineWaitsForReconnectionToStartCounting() {
        doReturn(false).when(service).isOnline(NODE_ID);
        assertEquals(0, service.saveSchedule(NODE_ID, 6).getCode());
        assertNull(stored.getRebootNextAt());

        clock.advance(10 * HOUR);
        jitter.set(3599);
        doReturn(true).when(service).isOnline(NODE_ID);
        service.onOnline(NODE_ID, false);

        assertEquals(clock.millis() + 6 * HOUR + 3_599_000L, stored.getRebootNextAt());
        verify(service, never()).sendReboot(anyLong(), anyString());
    }

    @Test
    void disconnectClearsTheDeadlineAndReconnectStartsAFreshPeriod() {
        service.saveSchedule(NODE_ID, 3);
        service.onOffline(NODE_ID);
        assertNull(stored.getRebootNextAt());

        clock.advance(8 * HOUR);
        service.onOnline(NODE_ID, false);

        assertEquals(clock.millis() + 3 * HOUR, stored.getRebootNextAt());
        assertEquals(3, stored.getRebootIntervalHours());
    }

    @Test
    void panelRestartPreservesAFuturePersistedDeadline() {
        long savedDeadline = clock.millis() + HOUR + 123_000;
        setSchedule(4, savedDeadline);
        jitter.set(3599);

        service.onOnline(NODE_ID, true);

        assertEquals(savedDeadline, stored.getRebootNextAt());
        verify(service, never()).sendReboot(anyLong(), anyString());
    }

    @Test
    void aSecondConnectionRebasesEvenIfThePersistedStatusIsStillOnline() {
        setSchedule(4, clock.millis() + HOUR);
        service.onOnline(NODE_ID, true);
        clock.advance(HOUR / 2);
        service.onOnline(NODE_ID, true);
        assertEquals(clock.millis() + 4 * HOUR, stored.getRebootNextAt());
    }

    @Test
    void shutdownPreservesTheDeadlineAndPreventsAlreadyQueuedWork() {
        List<Runnable> queue = new ArrayList<>();
        service = createService(queue::add);
        setSchedule(1, clock.millis());
        service.onReady();
        service.enqueueDueReboots();
        assertEquals(1, queue.size());

        service.onStopping();
        service.onOffline(NODE_ID);
        queue.get(0).run();
        service.enqueueDueReboots();

        assertEquals(clock.millis(), stored.getRebootNextAt());
        verify(service, never()).sendReboot(anyLong(), anyString());
    }

    @Test
    void panelRestartRebasesAnOverdueDeadlineWithoutCatchingUp() {
        setSchedule(4, clock.millis() - HOUR);
        service.onOnline(NODE_ID, true);
        service.onReady();
        service.enqueueDueReboots();

        assertEquals(clock.millis() + 4 * HOUR, stored.getRebootNextAt());
        verify(service, never()).sendReboot(anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedOrUnacknowledgedScheduledRebootStopsInsteadOfRepeating(boolean timeout) {
        setSchedule(1, clock.millis());
        doReturn(timeout ? null : response(-1, "权限不足")).when(service).sendReboot(eq(NODE_ID), anyString());
        service.onReady();

        service.enqueueDueReboots();
        assertNull(stored.getRebootNextAt());
        clock.advance(24 * HOUR);
        service.enqueueDueReboots();
        service.enqueueDueReboots();

        assertEquals(1, stored.getRebootIntervalHours());
        verify(service, times(1)).sendReboot(eq(NODE_ID), anyString());
        verify(mapper, times(1)).claimDueReboot(eq(NODE_ID), anyLong(), anyLong());
    }

    @Test
    void transportExceptionIsReportedAsUnknownWithoutSendingAgain() {
        doThrow(new IllegalStateException("socket closed")).when(service).sendReboot(eq(NODE_ID), anyString());

        assertEquals(-2, service.reboot(NODE_ID).getCode());
        assertEquals(-1, service.reboot(NODE_ID).getCode());
        verify(service, times(1)).sendReboot(eq(NODE_ID), anyString());
    }

    @Test
    void schedulerWaitsForMigrationsAndDeduplicatesQueuedWork() {
        List<Runnable> queue = new ArrayList<>();
        service = createService(queue::add);
        setSchedule(1, clock.millis());

        service.enqueueDueReboots();
        verify(mapper, never()).findDueReboots(anyLong());
        assertTrue(queue.isEmpty());
        service.onReady();
        service.enqueueDueReboots();
        service.enqueueDueReboots();

        assertEquals(1, queue.size());
        queue.get(0).run();
        verify(service, times(1)).sendReboot(eq(NODE_ID), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8})
    void changingAPlanInvalidatesWorkAlreadyInTheQueue(int newHours) {
        List<Runnable> queue = new ArrayList<>();
        service = createService(queue::add);
        setSchedule(1, clock.millis());
        service.onReady();
        service.enqueueDueReboots();

        service.saveSchedule(NODE_ID, newHours);
        Long newDeadline = stored.getRebootNextAt();
        queue.get(0).run();

        assertEquals(newHours, stored.getRebootIntervalHours());
        assertEquals(newDeadline, stored.getRebootNextAt());
        verify(service, never()).sendReboot(anyLong(), anyString());
        verify(mapper, never()).claimDueReboot(anyLong(), anyLong(), anyLong());
    }

    @Test
    void aLostDatabaseClaimDoesNotSendTheCommand() {
        setSchedule(1, clock.millis());
        doReturn(0).when(mapper).claimDueReboot(anyLong(), anyLong(), anyLong());
        service.onReady();

        service.enqueueDueReboots();

        verify(service, never()).sendReboot(anyLong(), anyString());
    }

    @Test
    void manualAndAlreadyQueuedScheduledRequestsCannotSendTwice() throws Exception {
        List<Runnable> queue = new ArrayList<>();
        service = createService(queue::add);
        setSchedule(2, clock.millis());
        service.onReady();
        service.enqueueDueReboots();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch acknowledge = new CountDownLatch(1);
        blockTransport(sending, acknowledge);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<R> manual = worker.submit(() -> service.reboot(NODE_ID));
            assertTrue(sending.await(5, TimeUnit.SECONDS));

            assertEquals(-1, service.reboot(NODE_ID).getCode());
            queue.get(0).run();
            acknowledge.countDown();

            assertEquals(0, manual.get(5, TimeUnit.SECONDS).getCode());
            verify(service, times(1)).sendReboot(eq(NODE_ID), anyString());
        } finally {
            acknowledge.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void aNewScheduleSavedWhileWaitingWinsOverTheOldAcknowledgement() throws Exception {
        setSchedule(2, clock.millis());
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch acknowledge = new CountDownLatch(1);
        blockTransport(sending, acknowledge);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<R> manual = worker.submit(() -> service.reboot(NODE_ID));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            service.saveSchedule(NODE_ID, 12);
            long newDeadline = stored.getRebootNextAt();
            clock.advance(HOUR);
            acknowledge.countDown();

            assertEquals(0, manual.get(5, TimeUnit.SECONDS).getCode());
            assertEquals(12, stored.getRebootIntervalHours());
            assertEquals(newDeadline, stored.getRebootNextAt());
        } finally {
            acknowledge.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void executionFailureOnlyAffectsItsOwnRequestAndPausesTheSchedule() {
        service.saveSchedule(NODE_ID, 2);
        assertEquals(0, service.reboot(NODE_ID).getCode());
        ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
        verify(service).sendReboot(eq(NODE_ID), request.capture());
        long acceptedDeadline = stored.getRebootNextAt();

        service.onExecutionFailure(NODE_ID, "unrelated-request", "permission denied");
        assertEquals(acceptedDeadline, stored.getRebootNextAt());
        service.onExecutionFailure(NODE_ID, request.getValue(), "permission denied");
        assertNull(stored.getRebootNextAt());
        verify(service).publish(any(Node.class), eq("reboot"), eq("failed"), eq("permission denied"));

        clock.advance(10 * HOUR);
        service.onReady();
        service.enqueueDueReboots();
        verify(service, times(1)).sendReboot(eq(NODE_ID), anyString());
    }

    @Test
    void failureArrivingBeforeTheAcknowledgementCannotBeOverwrittenBySuccess() {
        service.saveSchedule(NODE_ID, 2);
        doAnswer(call -> {
            service.onExecutionFailure(NODE_ID, call.getArgument(1), "reboot unavailable");
            return response(0, "OK");
        }).when(service).sendReboot(eq(NODE_ID), anyString());

        R result = service.reboot(NODE_ID);

        assertEquals(-1, result.getCode());
        assertEquals("reboot unavailable", result.getMsg());
        assertNull(stored.getRebootNextAt());
    }

    @Test
    void reconnectClearsTheOldRequestBeforeAcceptingAnotherReboot() {
        service.saveSchedule(NODE_ID, 2);
        service.reboot(NODE_ID);
        service.onOffline(NODE_ID);
        clock.advance(TimeUnit.MINUTES.toMillis(1));
        service.onOnline(NODE_ID, false);
        service.reboot(NODE_ID);
        ArgumentCaptor<String> requests = ArgumentCaptor.forClass(String.class);
        verify(service, times(2)).sendReboot(eq(NODE_ID), requests.capture());
        long newDeadline = stored.getRebootNextAt();

        assertNotEquals(requests.getAllValues().get(0), requests.getAllValues().get(1));
        service.onExecutionFailure(NODE_ID, requests.getAllValues().get(0), "stale failure");

        assertEquals(newDeadline, stored.getRebootNextAt());
    }

    @Test
    void missingOfflineAndOldNodesNeverReceiveRebootCommands() {
        assertEquals(-1, service.reboot(999L).getCode());
        doReturn(false).when(service).isOnline(NODE_ID);
        assertEquals(-1, service.reboot(NODE_ID).getCode());
        doReturn(true).when(service).isOnline(NODE_ID);
        stored.setVersion("3.1.5");
        assertEquals(-1, service.reboot(NODE_ID).getCode());
        assertEquals(-1, service.saveSchedule(NODE_ID, 2).getCode());

        verify(service, never()).sendReboot(anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.1.6", "v3.1.6", "3.1.10", "3.2.0", "4.0.0"})
    void supportedNodeVersionsAreComparedNumerically(String version) {
        assertTrue(NodeRebootService.supportsReboot(version));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "3.1.5", "3.0.99", "2.9.99", "3.1.6-preview", "unknown", "999999999999.1.1"})
    void oldOrUnknownVersionsAreRejected(String version) {
        assertFalse(NodeRebootService.supportsReboot(version));
        assertFalse(NodeRebootService.supportsReboot(null));
    }

    private NodeRebootService createService(Executor executor) {
        NodeRebootService result = spy(new NodeRebootService(mapper, executor, clock, jitter::get));
        doReturn(true).when(result).isOnline(NODE_ID);
        doReturn(response(0, "OK")).when(result).sendReboot(anyLong(), anyString());
        doNothing().when(result).publish(any(Node.class), anyString(), nullable(String.class), nullable(String.class));
        return result;
    }

    private void blockTransport(CountDownLatch sending, CountDownLatch acknowledge) {
        doAnswer(call -> {
            sending.countDown();
            if (!acknowledge.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test acknowledgement timed out");
            return response(0, "OK");
        }).when(service).sendReboot(eq(NODE_ID), anyString());
    }

    private void setSchedule(int hours, Long deadline) {
        stored.setRebootIntervalHours(hours);
        stored.setRebootNextAt(deadline);
    }

    private Node snapshot() {
        synchronized (stored) {
            Node node = new Node();
            node.setId(stored.getId());
            node.setVersion(stored.getVersion());
            node.setRebootIntervalHours(stored.getRebootIntervalHours());
            node.setRebootNextAt(stored.getRebootNextAt());
            return node;
        }
    }

    private static GostDto response(int code, String message) {
        GostDto response = new GostDto();
        response.setCode(code);
        response.setMsg(message);
        return response;
    }

    private static final class MutableClock extends Clock {
        private long now;

        private MutableClock(long now) { this.now = now; }
        private void advance(long milliseconds) { now += milliseconds; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
