package com.admin.service;

import com.admin.common.dto.NodeUsageConfirmDto;
import com.admin.common.dto.UsageSnapshot;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.NodeVpsUsage;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.NodeVpsUsageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One current VPS and at most two retired VPS records per logical node. */
@Service
public class NodeUsageService {
    private final NodeMapper nodes;
    private final NodeVpsUsageMapper usages;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ConcurrentHashMap<Long, PendingCandidate> candidates = new ConcurrentHashMap<>();

    @Autowired
    public NodeUsageService(NodeMapper nodes, NodeVpsUsageMapper usages, TransactionTemplate transactions) {
        this(nodes, usages, transactions, Clock.systemUTC());
    }

    NodeUsageService(NodeMapper nodes, NodeVpsUsageMapper usages, TransactionTemplate transactions, Clock clock) {
        this.nodes = nodes;
        this.usages = usages;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record UsageRecord(Long id, long startedAt, Long replacedAt, long uploadBytes, long downloadBytes,
                              long totalBytes, long durationSeconds, long lastReportedAt, String address) {}
    public record Candidate(String candidateId, long detectedAt, String address, String reason, boolean canKeepCurrent) {}
    public record UsageSummary(String status, UsageRecord current, Candidate pending) {}
    public record UsageHistory(String status, UsageRecord current, Candidate pending, List<UsageRecord> records) {}
    public record AdmissionDecision(boolean accepted, Long usageId, UsageSummary summary) {}
    public record ConfirmationResult(String acceptedSessionId, Long usageId, UsageSummary summary) {}

    /** Caller holds the same lifecycle lock used to replace/close the node's WebSocket. */
    public AdmissionDecision admit(Long nodeId, String sessionId, UsageSnapshot snapshot,
                                   boolean otherActive, String address) {
        requireNodeId(nodeId);
        if (sessionId == null || sessionId.isBlank() || snapshot == null) {
            throw new IllegalArgumentException("节点统计连接信息不完整");
        }
        AdmissionDecision result = transactions.execute(status -> {
            Node node = lockNode(nodeId);
            NodeVpsUsage current = current(node);
            if (current == null) {
                if (!otherActive && snapshot.hasStrongIdentity() && !isArchivedIdentity(nodeId, snapshot)) {
                    return accepted(createRecord(nodeId, null, snapshot, address, false), sessionId);
                }
                return pending(nodeId, sessionId, current, snapshot, address,
                        otherActive ? "另一台 VPS 仍在线，请确认新连接的归属" : "VPS 身份信息不足，请确认后开始统计");
            }

            Identity comparison = compare(current, snapshot);
            if (comparison == Identity.SAME || approvedWeakMatch(current, snapshot, comparison)) {
                if (otherActive && (differentKnown(current.getInstallationId(), snapshot.installationId())
                        || differentKnown(current.getMacId(), snapshot.macId()))) {
                    return pending(nodeId, sessionId, current, snapshot, address,
                            "检测到多份节点安装同时连接，请确认 VPS 归属");
                }
                reconcile(current, snapshot, true);
                mergeIdentity(current, snapshot);
                rememberAddress(current, address);
                current.setLastReportedAt(clock.millis());
                usages.updateById(current);
                return accepted(current, sessionId);
            }
            boolean archived = isArchivedIdentity(nodeId, snapshot);
            if (comparison == Identity.DIFFERENT && !otherActive && !archived) {
                return accepted(createRecord(nodeId, current, snapshot, address, false), sessionId);
            }
            String reason = otherActive ? "另一台 VPS 仍在线，请确认新连接的归属"
                    : archived ? "曾经使用的 VPS 再次连接，请确认是否继续使用"
                    : "无法确认这是否为原 VPS，请选择保留统计或更换 VPS";
            return pending(nodeId, sessionId, current, snapshot, address, reason);
        });
        if (result.accepted()) discardCandidate(nodeId, sessionId);
        return result;
    }

    /** Absolute meter checkpoints make skipped, repeated and reordered samples harmless. */
    public UsageSummary acceptSample(Long nodeId, Long usageId, UsageSnapshot snapshot) {
        requireNodeId(nodeId);
        if (snapshot == null) throw new IllegalArgumentException("节点统计数据不能为空");
        return transactions.execute(status -> {
            Node node = lockNode(nodeId);
            NodeVpsUsage current = current(node);
            if (current == null || !Objects.equals(usageId, node.getCurrentUsageId())) return summaryOf(nodeId, current);
            Identity comparison = compare(current, snapshot);
            if (comparison != Identity.SAME && !approvedWeakMatch(current, snapshot, comparison)) return summaryOf(nodeId, current);
            if (reconcile(current, snapshot, false)) {
                mergeIdentity(current, snapshot);
                current.setLastReportedAt(clock.millis());
                usages.updateById(current);
            }
            return summaryOf(nodeId, current);
        });
    }

    public UsageSummary summary(Long nodeId) {
        requireNodeId(nodeId);
        return summaryOf(nodeId, usages.findCurrent(nodeId));
    }

    public R history(Long nodeId) {
        requireNodeId(nodeId);
        if (nodes.selectById(nodeId) == null) return R.err("节点不存在");
        try {
            return transactions.execute(status -> {
                Node node = lockNode(nodeId);
                UsageSummary summary = summaryOf(nodeId, current(node));
                List<UsageRecord> records = usages.listByNode(nodeId).stream().limit(3).map(this::record).toList();
                return R.ok(new UsageHistory(summary.status(), summary.current(), summary.pending(), records));
            });
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
    }

    /** Caller verifies that the pending session is still open before entering this transaction. */
    public ConfirmationResult confirm(NodeUsageConfirmDto request) {
        if (request == null) throw new IllegalArgumentException("确认信息不能为空");
        requireNodeId(request.getId());
        if (!"same".equals(request.getDecision()) && !"replace".equals(request.getDecision())) {
            throw new IllegalArgumentException("请选择保留统计或更换 VPS");
        }
        Confirmed confirmed = transactions.execute(status -> {
            Node node = lockNode(request.getId());
            PendingCandidate candidate = candidates.get(request.getId());
            if (candidate == null || !candidate.id.equals(request.getCandidateId())
                    || !Objects.equals(node.getCurrentUsageId(), request.getExpectedUsageId())) {
                throw new IllegalArgumentException("待确认的 VPS 或当前统计已变化，请刷新后重新确认");
            }
            NodeVpsUsage current = current(node);
            if ("same".equals(request.getDecision())) {
                if (current == null) throw new IllegalArgumentException("还没有当前 VPS 统计，请选择开始新的统计");
                reconcile(current, candidate.snapshot, true);
                mergeIdentity(current, candidate.snapshot);
                current.setWeakIdentityApproved(!candidate.snapshot.installationId().isEmpty());
                rememberAddress(current, candidate.address);
                current.setLastReportedAt(clock.millis());
                usages.updateById(current);
            } else {
                current = createRecord(request.getId(), current, candidate.snapshot, candidate.address, true);
            }
            return new Confirmed(new ConfirmationResult(candidate.sessionId, current.getId(),
                    new UsageSummary("active", record(current), null)), candidate);
        });
        candidates.remove(request.getId(), confirmed.candidate);
        return confirmed.result;
    }

    public void discardCandidate(Long nodeId, String sessionId) {
        if (nodeId == null || sessionId == null) return;
        candidates.computeIfPresent(nodeId, (id, candidate) -> candidate.sessionId.equals(sessionId) ? null : candidate);
    }

    public void forget(Long nodeId) {
        if (nodeId != null) candidates.remove(nodeId);
    }

    private Node lockNode(Long nodeId) {
        Node node = usages.lockNode(nodeId);
        if (node == null) throw new IllegalArgumentException("节点不存在");
        return node;
    }

    private NodeVpsUsage current(Node node) {
        if (node.getCurrentUsageId() == null) return null;
        NodeVpsUsage current = usages.selectById(node.getCurrentUsageId());
        if (current == null || !Objects.equals(current.getNodeId(), node.getId()) || current.getReplacedAt() != null) {
            throw new IllegalArgumentException("当前 VPS 统计记录异常，请刷新后重试");
        }
        return current;
    }

    private NodeVpsUsage createRecord(Long nodeId, NodeVpsUsage previous, UsageSnapshot snapshot,
                                     String address, boolean manuallyApproved) {
        long now = clock.millis();
        if (previous != null) {
            previous.setReplacedAt(now);
            usages.updateById(previous);
        }
        NodeVpsUsage record = new NodeVpsUsage();
        record.setNodeId(nodeId);
        record.setHardwareId(snapshot.hardwareId());
        record.setSystemId(snapshot.systemId());
        record.setMacId(snapshot.macId());
        record.setInstallationId(snapshot.installationId());
        record.setWeakIdentityApproved(manuallyApproved && !snapshot.installationId().isEmpty());
        record.setStartedAt(now);
        record.setAddress(normalizeAddress(address));
        record.setUploadBytes(0L);
        record.setDownloadBytes(0L);
        record.setMeterEpoch(snapshot.meterEpoch());
        setCheckpoint(record, snapshot);
        record.setLastReportedAt(now);
        usages.insert(record);
        if (record.getId() == null || usages.setCurrent(nodeId, record.getId()) != 1) {
            throw new IllegalStateException("无法保存当前 VPS 统计");
        }
        List<NodeVpsUsage> retained = usages.listByNode(nodeId);
        for (int i = 3; i < retained.size(); i++) usages.deleteById(retained.get(i).getId());
        return record;
    }

    private boolean reconcile(NodeVpsUsage current, UsageSnapshot snapshot, boolean freshAdmission) {
        if (!current.getMeterEpoch().equals(snapshot.meterEpoch())) {
            if (!freshAdmission) return false;
            // An unknown epoch could be a new meter or an old backup: retain totals and establish a new baseline.
            current.setMeterEpoch(snapshot.meterEpoch());
            setCheckpoint(current, snapshot);
            return true;
        }
        if (snapshot.sequence() < current.getLastSequence() && freshAdmission) {
            // A same-boot restore can have a rewound sequence but caught-up counters. Keep that verifiable delta.
            if (snapshot.uploadBytes() >= current.getCheckpointUploadBytes()
                    && snapshot.downloadBytes() >= current.getCheckpointDownloadBytes()) {
                addTotals(current, snapshot.uploadBytes() - current.getCheckpointUploadBytes(),
                        snapshot.downloadBytes() - current.getCheckpointDownloadBytes());
            }
            setCheckpoint(current, snapshot);
            return true;
        }
        if (snapshot.sequence() <= current.getLastSequence()
                || snapshot.uploadBytes() < current.getCheckpointUploadBytes()
                || snapshot.downloadBytes() < current.getCheckpointDownloadBytes()) return false;
        addTotals(current, snapshot.uploadBytes() - current.getCheckpointUploadBytes(),
                snapshot.downloadBytes() - current.getCheckpointDownloadBytes());
        setCheckpoint(current, snapshot);
        return true;
    }

    private void addTotals(NodeVpsUsage current, long upload, long download) {
        try {
            long nextUpload = Math.addExact(current.getUploadBytes(), upload);
            long nextDownload = Math.addExact(current.getDownloadBytes(), download);
            Math.addExact(nextUpload, nextDownload);
            current.setUploadBytes(nextUpload);
            current.setDownloadBytes(nextDownload);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("累计流量已超出可保存范围");
        }
    }

    private static void setCheckpoint(NodeVpsUsage current, UsageSnapshot snapshot) {
        current.setLastSequence(snapshot.sequence());
        current.setCheckpointUploadBytes(snapshot.uploadBytes());
        current.setCheckpointDownloadBytes(snapshot.downloadBytes());
    }

    private AdmissionDecision accepted(NodeVpsUsage current, String sessionId) {
        PendingCandidate candidate = candidates.get(current.getNodeId());
        if (candidate != null && candidate.sessionId.equals(sessionId)) candidate = null;
        return new AdmissionDecision(true, current.getId(), summaryOf(current, candidate));
    }

    private AdmissionDecision pending(Long nodeId, String sessionId, NodeVpsUsage current,
                                      UsageSnapshot snapshot, String address, String reason) {
        PendingCandidate candidate = candidates.compute(nodeId, (id, previous) -> {
            boolean same = previous != null && sameCandidate(previous.snapshot, snapshot);
            UsageSnapshot latest = snapshot;
            if (same && previous.snapshot.meterEpoch().equals(snapshot.meterEpoch())
                    && previous.snapshot.sequence() > snapshot.sequence()
                    && previous.sessionId.equals(sessionId)) latest = previous.snapshot;
            return new PendingCandidate(same ? previous.id : UUID.randomUUID().toString(), sessionId,
                    same ? previous.detectedAt : clock.millis(), normalizeAddress(address), reason, latest);
        });
        return new AdmissionDecision(false, null, summaryOf(current, candidate));
    }

    private boolean isArchivedIdentity(Long nodeId, UsageSnapshot snapshot) {
        return usages.listByNode(nodeId).stream().anyMatch(record -> record.getReplacedAt() != null
                && (compare(record, snapshot) == Identity.SAME
                || approvedWeakMatch(record, snapshot, compare(record, snapshot))));
    }

    private static boolean sameCandidate(UsageSnapshot first, UsageSnapshot second) {
        if (differentKnown(first.installationId(), second.installationId())
                || differentKnown(first.macId(), second.macId())) return false;
        Identity identity = compare(first.hardwareId(), first.systemId(), second.hardwareId(), second.systemId());
        return identity == Identity.SAME || identity == Identity.UNKNOWN
                && !first.installationId().isEmpty() && first.installationId().equals(second.installationId());
    }

    private static Identity compare(NodeVpsUsage current, UsageSnapshot snapshot) {
        return compare(current.getHardwareId(), current.getSystemId(), snapshot.hardwareId(), snapshot.systemId());
    }

    private static Identity compare(String oldHardware, String oldSystem, String hardware, String system) {
        boolean comparableHardware = hasText(oldHardware) && hasText(hardware);
        boolean comparableSystem = hasText(oldSystem) && hasText(system);
        if (comparableHardware && !oldHardware.equals(hardware) || comparableSystem && !oldSystem.equals(system)) {
            return Identity.DIFFERENT;
        }
        return comparableHardware || comparableSystem ? Identity.SAME : Identity.UNKNOWN;
    }

    private static boolean approvedWeakMatch(NodeVpsUsage current, UsageSnapshot snapshot, Identity comparison) {
        return comparison == Identity.UNKNOWN && Boolean.TRUE.equals(current.getWeakIdentityApproved())
                && !snapshot.installationId().isEmpty() && snapshot.installationId().equals(current.getInstallationId());
    }

    private static void mergeIdentity(NodeVpsUsage current, UsageSnapshot snapshot) {
        if (!snapshot.hardwareId().isEmpty()) current.setHardwareId(snapshot.hardwareId());
        if (!snapshot.systemId().isEmpty()) current.setSystemId(snapshot.systemId());
        if (!snapshot.macId().isEmpty()) current.setMacId(snapshot.macId());
        if (!snapshot.installationId().isEmpty()) current.setInstallationId(snapshot.installationId());
    }

    private UsageSummary summaryOf(Long nodeId, NodeVpsUsage current) {
        return summaryOf(current, candidates.get(nodeId));
    }

    private UsageSummary summaryOf(NodeVpsUsage current, PendingCandidate pending) {
        Candidate candidate = pending == null ? null : new Candidate(pending.id, pending.detectedAt,
                pending.address, pending.reason, current != null);
        return new UsageSummary(candidate != null ? "pending" : current == null ? "unsupported" : "active",
                current == null ? null : record(current), candidate);
    }

    private UsageRecord record(NodeVpsUsage record) {
        long end = record.getReplacedAt() == null ? clock.millis() : record.getReplacedAt();
        return new UsageRecord(record.getId(), record.getStartedAt(), record.getReplacedAt(),
                record.getUploadBytes(), record.getDownloadBytes(), record.getUploadBytes() + record.getDownloadBytes(),
                Math.max(0L, (end - record.getStartedAt()) / 1000), record.getLastReportedAt(), record.getAddress());
    }

    private static void rememberAddress(NodeVpsUsage current, String address) {
        if (hasText(address)) current.setAddress(normalizeAddress(address));
    }

    private static String normalizeAddress(String address) {
        String normalized = address == null ? "" : address.trim();
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean differentKnown(String first, String second) {
        return hasText(first) && hasText(second) && !first.equals(second);
    }

    private static void requireNodeId(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("节点ID必须大于0");
    }

    private enum Identity { SAME, DIFFERENT, UNKNOWN }
    private record PendingCandidate(String id, String sessionId, long detectedAt, String address,
                                    String reason, UsageSnapshot snapshot) {}
    private record Confirmed(ConfirmationResult result, PendingCandidate candidate) {}
}
