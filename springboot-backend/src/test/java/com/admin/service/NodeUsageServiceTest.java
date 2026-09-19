package com.admin.service;

import com.admin.common.dto.NodeUsageConfirmDto;
import com.admin.common.dto.UsageSnapshot;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.NodeVpsUsage;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.NodeVpsUsageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeUsageServiceTest {
    private static final long NODE = 7;
    private static final String INSTALL = "aaaaaaaa-1111-2222-3333-000000000001";
    private static final String EPOCH = "aaaaaaaa-1111-2222-3333-000000000002";
    private static final String NEW_EPOCH = "aaaaaaaa-1111-2222-3333-000000000003";
    private final MutableClock clock = new MutableClock();
    private final Map<Long, NodeVpsUsage> rows = new HashMap<>();
    private long nextId = 1;
    private Node node;
    private NodeMapper nodes;
    private NodeVpsUsageMapper usages;
    private TransactionTemplate transactions;
    private NodeUsageService service;

    @BeforeEach
    void setUp() {
        node = new Node();
        node.setId(NODE);
        nodes = mock(NodeMapper.class);
        usages = mock(NodeVpsUsageMapper.class);
        when(nodes.selectById(anyLong())).thenAnswer(call -> call.<Long>getArgument(0) == NODE ? copy(node) : null);
        when(usages.lockNode(anyLong())).thenAnswer(call -> call.<Long>getArgument(0) == NODE ? copy(node) : null);
        when(usages.selectById(anyLong())).thenAnswer(call -> copy(rows.get(call.<Long>getArgument(0))));
        when(usages.findCurrent(NODE)).thenAnswer(call -> copy(rows.get(node.getCurrentUsageId())));
        when(usages.listByNode(NODE)).thenAnswer(call -> rows.values().stream()
                .sorted(Comparator.comparing(NodeVpsUsage::getId).reversed()).map(NodeUsageServiceTest::copy).toList());
        when(usages.insert(any(NodeVpsUsage.class))).thenAnswer(call -> {
            NodeVpsUsage record = call.getArgument(0);
            record.setId(nextId++);
            rows.put(record.getId(), copy(record));
            return 1;
        });
        when(usages.updateById(any(NodeVpsUsage.class))).thenAnswer(call -> {
            NodeVpsUsage record = call.getArgument(0);
            rows.put(record.getId(), copy(record));
            return 1;
        });
        when(usages.setCurrent(eq(NODE), anyLong())).thenAnswer(call -> {
            node.setCurrentUsageId(call.getArgument(1));
            return 1;
        });
        when(usages.deleteById(anyLong())).thenAnswer(call -> rows.remove(call.<Long>getArgument(0)) == null ? 0 : 1);
        // An isolated transactional store: copied reads, row serialization, and rollback on failure.
        transactions = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                synchronized (rows) {
                    Map<Long, NodeVpsUsage> previous = new HashMap<>();
                    rows.forEach((id, row) -> previous.put(id, copy(row)));
                    Long previousCurrent = node.getCurrentUsageId();
                    try {
                        return callback.doInTransaction(new SimpleTransactionStatus());
                    } catch (RuntimeException e) {
                        rows.clear();
                        rows.putAll(previous);
                        node.setCurrentUsageId(previousCurrent);
                        throw e;
                    }
                }
            }
        };
        service = new NodeUsageService(nodes, usages, transactions, clock);
    }

    @Test
    void firstReportStartsAtZeroAndOnlyNewTrafficIsCounted() {
        var initial = admit("first", snapshot(1, 1, 10, 1_000, 2_000), false);
        assertTrue(initial.accepted());
        assertEquals(0, initial.summary().current().totalBytes());
        assertEquals(clock.millis(), initial.summary().current().startedAt());

        var updated = service.acceptSample(NODE, initial.usageId(), snapshot(1, 1, 11, 1_300, 2_700));

        assertEquals(300, updated.current().uploadBytes());
        assertEquals(700, updated.current().downloadBytes());
        assertEquals(1_000, updated.current().totalBytes());
    }

    @Test
    void duplicatesOutOfOrderAndOneRegressedCounterDoNotAdvanceTheCheckpoint() {
        Long id = admit("first", snapshot(1, 1, 10, 100, 100), false).usageId();
        service.acceptSample(NODE, id, snapshot(1, 1, 12, 300, 400));
        service.acceptSample(NODE, id, snapshot(1, 1, 12, 500, 500));
        service.acceptSample(NODE, id, snapshot(1, 1, 11, 600, 600));
        service.acceptSample(NODE, id, snapshot(1, 1, 13, 250, 800));
        assertEquals(500, service.summary(NODE).current().totalBytes());

        service.acceptSample(NODE, id, snapshot(1, 1, 14, 600, 700));
        assertEquals(1_100, service.summary(NODE).current().totalBytes());
    }

    @Test
    void concurrentCopiesOfOneSampleAreCountedOnce() throws Exception {
        Long id = admit("first", snapshot(1, 1, 1, 0, 0), false).usageId();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(workers.submit(() -> service.acceptSample(NODE, id, snapshot(1, 1, 2, 300, 700))));
            }
            for (Future<?> future : futures) future.get();
        } finally {
            workers.shutdownNow();
        }
        assertEquals(1_000, service.summary(NODE).current().totalBytes());
    }

    @Test
    void reconnectIpChangeAndPanelRestartKeepTheSameRecordAndStartTime() {
        var first = admit("before-reboot", snapshot(1, 1, 1, 0, 0), false);
        long startedAt = first.summary().current().startedAt();
        clock.advance(60_000);
        var reconnect = service.admit(NODE, "after-reboot", snapshot(1, 1, 2, 300, 700), false, "203.0.113.9");
        assertEquals(first.usageId(), reconnect.usageId());
        assertEquals("203.0.113.9", reconnect.summary().current().address());

        service = new NodeUsageService(nodes, usages, transactions, clock);
        var afterPanelRestart = admit("after-panel-restart", snapshot(1, 1, 3, 500, 900), false);

        assertEquals(first.usageId(), afterPanelRestart.usageId());
        assertEquals(startedAt, afterPanelRestart.summary().current().startedAt());
        assertEquals(1_400, afterPanelRestart.summary().current().totalBytes());
        assertEquals(1, rows.size());
    }

    @Test
    void missingOneStrongFieldDoesNotErasePreviouslyKnownIdentity() {
        var first = admit("first", snapshot(1, 1, 1, 0, 0), false);
        UsageSnapshot partial = new UsageSnapshot(hash(1), "", "", INSTALL, EPOCH, 2, 1, 1);
        var second = admit("partial", partial, true);

        assertTrue(second.accepted());
        assertEquals(first.usageId(), second.usageId());
        assertEquals(hash(1), rows.get(first.usageId()).getSystemId());
    }

    @Test
    void clonedStrongIdsWithDifferentInstallationCannotTakeOverAnActiveConnection() {
        Long original = admit("first", snapshot(1, 1, 1, 0, 0), false).usageId();
        UsageSnapshot clone = new UsageSnapshot(hash(1), hash(1), "", NEW_EPOCH, EPOCH, 2, 100, 100);

        var pending = admit("clone", clone, true);

        assertFalse(pending.accepted());
        assertEquals(original, node.getCurrentUsageId());
        assertTrue(pending.summary().pending().reason().contains("多份节点安装"));
        assertEquals(0, pending.summary().current().totalBytes());
    }

    @Test
    void macDisagreementRequiresConfirmationOnlyWhileAnotherConnectionIsActive() {
        UsageSnapshot initial = new UsageSnapshot(hash(1), hash(1), hash(1), INSTALL, EPOCH, 1, 0, 0);
        Long original = admit("first", initial, false).usageId();
        UsageSnapshot changedMac = new UsageSnapshot(hash(1), hash(1), hash(2), INSTALL, EPOCH, 2, 100, 100);

        assertFalse(admit("changed-mac", changedMac, true).accepted());
        var offlineReconnect = admit("changed-mac", changedMac, false);

        assertTrue(offlineReconnect.accepted());
        assertEquals(original, offlineReconnect.usageId());
        assertEquals(1, rows.size());
    }

    @Test
    void offlineTimeStillCountsAsNaturalDurationButRetiredDurationStops() {
        var first = admit("first", snapshot(1, 1, 1, 0, 0), false);
        clock.advance(86_400_000);
        assertEquals(86_400, service.summary(NODE).current().durationSeconds());
        admit("new-vps", snapshot(2, 2, 1, 0, 0), false);
        clock.advance(86_400_000);

        var history = (NodeUsageService.UsageHistory) service.history(NODE).getData();
        var retired = history.records().stream().filter(record -> record.id().equals(first.usageId())).findFirst().orElseThrow();
        assertEquals(86_400, retired.durationSeconds());
        assertEquals(86_400, history.current().durationSeconds());
    }

    @Test
    void reinstallingTheSystemCreatesANewRecordEvenOnTheSameHardware() {
        var previous = admit("old-system", snapshot(1, 1, 1, 0, 0), false);
        service.acceptSample(NODE, previous.usageId(), snapshot(1, 1, 2, 100, 200));
        clock.advance(60_000);

        var reinstalled = admit("new-system", snapshot(1, 2, 99, 9_000, 9_000), false);

        assertTrue(reinstalled.accepted());
        assertNotEquals(previous.usageId(), reinstalled.usageId());
        assertEquals(0, reinstalled.summary().current().totalBytes());
        assertEquals(clock.millis(), rows.get(previous.usageId()).getReplacedAt());
        assertEquals(100, rows.get(previous.usageId()).getUploadBytes());
    }

    @Test
    void hardLimitRetainsOnlyCurrentAndTwoPreviousVpsRecords() {
        for (int identity = 1; identity <= 6; identity++) {
            clock.advance(1_000);
            assertTrue(admit("vps-" + identity, snapshot(identity, identity, 1, 100, 200), false).accepted());
            assertTrue(rows.size() <= 3);
        }

        var history = (NodeUsageService.UsageHistory) service.history(NODE).getData();
        assertEquals(List.of(6L, 5L, 4L), history.records().stream().map(NodeUsageService.UsageRecord::id).toList());
        assertNull(history.records().get(0).replacedAt());
        assertNotNull(history.records().get(1).replacedAt());
        assertNotNull(history.records().get(2).replacedAt());
    }

    @Test
    void aRetainedHistoricalVpsReturningNeedsConfirmationAndCannotFlipCurrent() {
        Long first = admit("first", snapshot(1, 1, 1, 0, 0), false).usageId();
        Long current = admit("second", snapshot(2, 2, 1, 0, 0), false).usageId();

        var returning = admit("old-vps-returning", snapshot(1, 1, 2, 900, 900), false);

        assertFalse(returning.accepted());
        assertEquals(current, node.getCurrentUsageId());
        assertTrue(returning.summary().pending().reason().contains("曾经"));
        assertEquals(0, rows.get(first).getUploadBytes());
        assertEquals(2, rows.size());
    }

    @Test
    void oldUsageIdCannotWriteIntoEitherTheNewOrRetiredRecord() {
        Long first = admit("first", snapshot(1, 1, 1, 0, 0), false).usageId();
        Long current = admit("second", snapshot(2, 2, 1, 0, 0), false).usageId();

        var result = service.acceptSample(NODE, first, snapshot(1, 1, 99, 999, 999));

        assertEquals(current, result.current().id());
        assertEquals(0, result.current().totalBytes());
        assertEquals(0, rows.get(first).getUploadBytes());
    }

    @Test
    void anAlreadyActiveLegacyConnectionPreventsAutomaticFirstAdmission() {
        var pending = admit("new-protocol", snapshot(1, 1, 1, 0, 0), true);
        assertFalse(pending.accepted());
        assertNull(pending.summary().current());
        assertFalse(pending.summary().pending().canKeepCurrent());
        assertTrue(rows.isEmpty());

        var confirmed = service.confirm(confirmation(pending, "replace"));
        assertEquals("new-protocol", confirmed.acceptedSessionId());
        assertEquals(0, confirmed.summary().current().totalBytes());
    }

    @Test
    void conflictingVpsDoesNotDisplaceActiveAndCandidateRefreshKeepsItsIdentityAndDetectionTime() {
        Long active = admit("active", snapshot(1, 1, 1, 0, 0), false).usageId();
        var first = admit("candidate", snapshot(2, 2, 10, 10, 20), true);
        clock.advance(60_000);
        var latest = admit("candidate", snapshot(2, 2, 11, 100, 200), true);
        admit("candidate", snapshot(2, 2, 9, 1, 2), true);

        assertEquals(first.summary().pending().candidateId(), latest.summary().pending().candidateId());
        assertEquals(first.summary().pending().detectedAt(), latest.summary().pending().detectedAt());
        assertEquals(active, node.getCurrentUsageId());
        var confirmed = service.confirm(confirmation(latest, "replace"));
        assertEquals("candidate", confirmed.acceptedSessionId());
        assertNull(confirmed.summary().pending());
        assertEquals(100, rows.get(confirmed.usageId()).getCheckpointUploadBytes());
        assertEquals(0, confirmed.summary().current().totalBytes());
    }

    @Test
    void staleCandidateOrCurrentVersionCannotBeConfirmed() {
        admit("active", snapshot(1, 1, 1, 0, 0), false);
        var old = admit("old-candidate", snapshot(2, 2, 1, 0, 0), true);
        NodeUsageConfirmDto stale = confirmation(old, "replace");
        var latest = admit("new-candidate", snapshot(3, 3, 1, 0, 0), true);

        assertThrows(IllegalArgumentException.class, () -> service.confirm(stale));
        var wrongCurrent = confirmation(latest, "replace");
        wrongCurrent.setExpectedUsageId(999L);
        assertThrows(IllegalArgumentException.class, () -> service.confirm(wrongCurrent));
        assertEquals(1, rows.size());
    }

    @Test
    void oldDisconnectDoesNotRemoveAReconnectedCandidateButCurrentDisconnectDoes() {
        admit("active", snapshot(1, 1, 1, 0, 0), false);
        var first = admit("old-candidate-session", snapshot(2, 2, 1, 0, 0), true);
        var latest = admit("new-candidate-session", snapshot(2, 2, 2, 0, 0), true);
        assertEquals(first.summary().pending().candidateId(), latest.summary().pending().candidateId());

        service.discardCandidate(NODE, "old-candidate-session");
        assertNotNull(service.summary(NODE).pending());
        service.discardCandidate(NODE, "new-candidate-session");
        assertNull(service.summary(NODE).pending());
        assertThrows(IllegalArgumentException.class, () -> service.confirm(confirmation(latest, "replace")));
    }

    @Test
    void macOrInstallationAloneCannotAutomaticallyCreateOrReplaceAVps() {
        UsageSnapshot weak = new UsageSnapshot("", "", hash(1), INSTALL, EPOCH, 1, 10, 20);
        var first = admit("weak", weak, false);
        assertFalse(first.accepted());
        assertTrue(rows.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.confirm(confirmation(first, "same")));
        service.forget(NODE);
        assertEquals("unsupported", service.summary(NODE).status());

        admit("strong", snapshot(1, 1, 1, 0, 0), false);
        var uncertain = admit("weak", weak, false);
        assertFalse(uncertain.accepted());
        var kept = service.confirm(confirmation(uncertain, "same"));
        var reconnected = admit("weak-reconnected", new UsageSnapshot("", "", hash(2), INSTALL, EPOCH, 2, 30, 40), false);
        assertTrue(reconnected.accepted());
        assertEquals(kept.usageId(), reconnected.usageId());
        assertEquals(1, rows.size());
    }

    @Test
    void strongFieldsWithNoComparableSourceRequireConfirmation() {
        UsageSnapshot hardwareOnly = new UsageSnapshot(hash(1), "", "", INSTALL, EPOCH, 1, 0, 0);
        admit("hardware", hardwareOnly, false);
        UsageSnapshot systemOnly = new UsageSnapshot("", hash(1), "", INSTALL, EPOCH, 2, 10, 20);

        assertFalse(admit("system", systemOnly, false).accepted());
        assertEquals(1, rows.size());
    }

    @Test
    void manualKeepPreservesTotalsAndStartTimeWhileUpdatingConfirmedIdentity() {
        var original = admit("first", snapshot(1, 1, 1, 0, 0), false);
        service.acceptSample(NODE, original.usageId(), snapshot(1, 1, 2, 100, 200));
        clock.advance(1_000);
        var candidate = admit("candidate", snapshot(2, 2, 3, 150, 250), true);

        var kept = service.confirm(confirmation(candidate, "same"));

        assertEquals(original.usageId(), kept.usageId());
        assertEquals(original.summary().current().startedAt(), kept.summary().current().startedAt());
        assertEquals(400, kept.summary().current().totalBytes());
        assertEquals(1, rows.size());
        assertTrue(admit("confirmed-reconnect", snapshot(2, 2, 4, 200, 300), false).accepted());
    }

    @Test
    void newMeterEpochPreservesTotalsAndRealignsOnlyOnReconnection() {
        Long id = admit("first", snapshot(1, 1, 1, 0, 0), false).usageId();
        service.acceptSample(NODE, id, snapshot(1, 1, 2, 100, 200));
        UsageSnapshot recreated = new UsageSnapshot(hash(1), hash(1), "", INSTALL, NEW_EPOCH, 1, 10, 20);

        service.acceptSample(NODE, id, recreated);
        assertEquals(300, service.summary(NODE).current().totalBytes());
        var admitted = admit("recreated-meter", recreated, false);
        assertEquals(id, admitted.usageId());
        assertEquals(300, admitted.summary().current().totalBytes());
        service.acceptSample(NODE, id, snapshot(1, 1, 99, 999, 999));
        assertEquals(300, service.summary(NODE).current().totalBytes());
        service.acceptSample(NODE, id, new UsageSnapshot(hash(1), hash(1), "", INSTALL, NEW_EPOCH, 2, 30, 50));
        assertEquals(350, service.summary(NODE).current().totalBytes());
    }

    @Test
    void restoringAnOlderEpochDoesNotCountItsHistoricalAbsoluteTotalsAgain() {
        Long id = admit("first", snapshot(1, 1, 1, 0, 0), false).usageId();
        service.acceptSample(NODE, id, snapshot(1, 1, 2, 100, 200));
        admit("new-epoch", new UsageSnapshot(hash(1), hash(1), "", INSTALL, NEW_EPOCH, 1, 0, 0), false);
        service.acceptSample(NODE, id, new UsageSnapshot(hash(1), hash(1), "", INSTALL, NEW_EPOCH, 2, 10, 20));

        var restored = admit("old-backup", snapshot(1, 1, 2, 100, 200), false);

        assertEquals(330, restored.summary().current().totalBytes());
        service.acceptSample(NODE, id, snapshot(1, 1, 3, 130, 240));
        assertEquals(400, service.summary(NODE).current().totalBytes());
    }

    @Test
    void freshSequenceRollbackKeepsTheDeltaWhenSameBootCountersHaveAlreadyCaughtUp() {
        Long id = admit("first", snapshot(1, 1, 10, 100, 100), false).usageId();
        service.acceptSample(NODE, id, snapshot(1, 1, 11, 200, 300));

        var restored = admit("restored", snapshot(1, 1, 5, 250, 400), false);

        assertEquals(450, restored.summary().current().totalBytes());
        assertEquals(5, rows.get(id).getLastSequence());
        service.acceptSample(NODE, id, snapshot(1, 1, 6, 270, 440));
        assertEquals(510, service.summary(NODE).current().totalBytes());
    }

    @Test
    void persistedSequenceRollbackRealignsOnlyOnFreshAdmissionAndNeverDecreasesTotal() {
        Long id = admit("first", snapshot(1, 1, 10, 100, 100), false).usageId();
        service.acceptSample(NODE, id, snapshot(1, 1, 11, 200, 300));
        UsageSnapshot restored = snapshot(1, 1, 5, 50, 50);
        service.acceptSample(NODE, id, restored);
        assertEquals(11, rows.get(id).getLastSequence());

        assertEquals(300, admit("restored", restored, false).summary().current().totalBytes());
        assertEquals(5, rows.get(id).getLastSequence());
        service.acceptSample(NODE, id, snapshot(1, 1, 6, 60, 80));
        assertEquals(340, service.summary(NODE).current().totalBytes());
    }

    @Test
    void failedNewRecordInsertRollsBackTheRetiredFlagAndCurrentPointer() {
        Long current = admit("first", snapshot(1, 1, 1, 0, 0), false).usageId();
        doThrow(new IllegalStateException("database unavailable")).when(usages).insert(any(NodeVpsUsage.class));

        assertThrows(IllegalStateException.class, () -> admit("replacement", snapshot(2, 2, 1, 0, 0), false));

        assertEquals(current, node.getCurrentUsageId());
        assertNull(rows.get(current).getReplacedAt());
        assertEquals(1, rows.size());
    }

    @Test
    void missingNodeAndInvalidConfirmDecisionCannotWriteRecords() {
        R history = service.history(999L);
        assertEquals(-1, history.getCode());
        assertThrows(IllegalArgumentException.class, () -> service.admit(999L, "unknown", snapshot(1, 1, 1, 0, 0), false, ""));
        var candidate = admit("weak", new UsageSnapshot("", "", "", INSTALL, EPOCH, 1, 0, 0), false);
        var invalid = confirmation(candidate, "drop");
        assertThrows(IllegalArgumentException.class, () -> service.confirm(invalid));
        assertTrue(rows.isEmpty());
    }

    private NodeUsageService.AdmissionDecision admit(String session, UsageSnapshot snapshot, boolean otherActive) {
        return service.admit(NODE, session, snapshot, otherActive, "203.0.113.1");
    }

    private NodeUsageConfirmDto confirmation(NodeUsageService.AdmissionDecision candidate, String decision) {
        NodeUsageConfirmDto dto = new NodeUsageConfirmDto();
        dto.setId(BigDecimal.valueOf(NODE));
        dto.setExpectedUsageId(candidate.summary().current() == null ? null : candidate.summary().current().id());
        dto.setCandidateId(candidate.summary().pending().candidateId());
        dto.setDecision(decision);
        return dto;
    }

    private static UsageSnapshot snapshot(int hardware, int system, long seq, long upload, long download) {
        return new UsageSnapshot(hash(hardware), hash(system), "", INSTALL, EPOCH, seq, upload, download);
    }

    private static String hash(int value) { return String.format("%064x", value); }

    private static Node copy(Node source) {
        if (source == null) return null;
        Node node = new Node();
        BeanUtils.copyProperties(source, node);
        return node;
    }

    private static NodeVpsUsage copy(NodeVpsUsage source) {
        if (source == null) return null;
        NodeVpsUsage record = new NodeVpsUsage();
        BeanUtils.copyProperties(source, record);
        return record;
    }

    private static class MutableClock extends Clock {
        private long now = Instant.parse("2026-09-20T08:00:00Z").toEpochMilli();
        void advance(long milliseconds) { now += milliseconds; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
