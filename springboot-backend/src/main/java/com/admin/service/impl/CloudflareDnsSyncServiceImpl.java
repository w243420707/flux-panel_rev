package com.admin.service.impl;

import com.admin.common.cloudflare.CloudflareApiClient;
import com.admin.common.cloudflare.CloudflareDnsRecord;
import com.admin.common.cloudflare.CloudflareDnsTarget;
import com.admin.common.lang.R;
import com.admin.common.utils.TunnelNodeUtil;
import com.admin.entity.CloudflareDnsBinding;
import com.admin.entity.CloudflareDnsSetting;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.service.CloudflareDnsBindingService;
import com.admin.service.CloudflareDnsSettingService;
import com.admin.service.CloudflareDnsSyncService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CloudflareDnsSyncServiceImpl implements CloudflareDnsSyncService {

    private static final String RECORD_TYPE_AUTO = "AUTO";
    private static final String RECORD_TYPE_A = "A";
    private static final String RECORD_TYPE_AAAA = "AAAA";
    private static final String RECORD_TYPE_CNAME = "CNAME";
    private static final String SYNC_SUCCESS = "SUCCESS";
    private static final String SYNC_FAILED = "FAILED";
    private static final String SYNC_SKIPPED = "SKIPPED";
    private static final String MANAGED_COMMENT_PREFIX = "flux-panel_rev";
    private static final List<String> MANAGED_RECORD_TYPES = Arrays.asList(RECORD_TYPE_A, RECORD_TYPE_AAAA, RECORD_TYPE_CNAME);
    private static final String WALL_STATUS_OK = "OK";
    private static final String WALL_STATUS_OBSERVING = "OBSERVING";
    private static final String WALL_STATUS_SUSPECTED_BLOCKED = "SUSPECTED_BLOCKED";
    private static final String WALL_STATUS_NODE_OFFLINE = "NODE_OFFLINE";
    private static final String WALL_STATUS_CHECK_FAILED = "CHECK_FAILED";
    private static final int NODE_ONLINE = 1;
    private static final long SMART_POOL_SWITCH_COOLDOWN_MS = 5 * 60 * 1000L;
    private static final long SMART_POOL_ROTATE_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    private static final ConcurrentHashMap<Long, Object> BINDING_LOCKS = new ConcurrentHashMap<>();

    @Resource
    private CloudflareDnsSettingService cloudflareDnsSettingService;

    @Resource
    private CloudflareDnsBindingService cloudflareDnsBindingService;

    @Resource
    private CloudflareApiClient cloudflareApiClient;

    @Resource
    private TunnelService tunnelService;

    @Resource
    private NodeService nodeService;

    @Override
    public R syncBinding(Long bindingId, String trigger) {
        CloudflareDnsBinding binding = cloudflareDnsBindingService.getBindingById(bindingId);
        if (binding == null) {
            return R.err("DNS 绑定不存在");
        }
        CloudflareDnsSetting setting = cloudflareDnsSettingService.getCurrentSetting();
        String readyMessage = validateSetting(setting);
        if (readyMessage != null) {
            markBinding(binding, SYNC_SKIPPED, readyMessage, null);
            return R.err(readyMessage);
        }
        if (!isEnabled(binding.getStatus())) {
            markBinding(binding, SYNC_SKIPPED, "DNS 绑定已禁用", null);
            return R.ok("DNS 绑定已禁用");
        }

        Object lock = BINDING_LOCKS.computeIfAbsent(binding.getId(), ignored -> new Object());
        synchronized (lock) {
            return syncBindingInternal(setting, binding, trigger);
        }
    }

    @Override
    public R syncAllBindings(String trigger) {
        CloudflareDnsSetting setting = cloudflareDnsSettingService.getCurrentSetting();
        String readyMessage = validateSetting(setting);
        if (readyMessage != null) {
            updateSettingSyncStatus(setting, SYNC_SKIPPED, readyMessage);
            return R.ok(readyMessage);
        }

        List<CloudflareDnsBinding> bindings = cloudflareDnsBindingService.list(
                new QueryWrapper<CloudflareDnsBinding>().eq("status", 1));
        int success = 0;
        int failed = 0;
        for (CloudflareDnsBinding binding : bindings) {
            R result = syncBinding(binding.getId(), trigger);
            if (result.getCode() == 0) {
                success++;
            } else {
                failed++;
            }
        }
        String message = "同步完成，成功 " + success + " 条，失败 " + failed + " 条";
        updateSettingSyncStatus(setting, failed == 0 ? SYNC_SUCCESS : SYNC_FAILED, message);
        return failed == 0 ? R.ok(message) : R.err(message);
    }

    @Override
    public R syncDueBindings() {
        CloudflareDnsSetting setting = cloudflareDnsSettingService.getCurrentSetting();
        if (!isEnabled(setting.getEnabled())) {
            return R.ok("Cloudflare DNS 未启用");
        }

        long now = System.currentTimeMillis();
        long intervalMillis = Math.max(resolveInterval(setting.getSyncIntervalSeconds()), 60) * 1000L;
        if (setting.getLastSyncAt() != null && now - setting.getLastSyncAt() < intervalMillis) {
            return R.ok("距离上次同步时间未超过间隔");
        }
        return syncAllBindings("scheduler");
    }

    @Override
    public R syncBindingsByTunnel(Long tunnelId, String trigger) {
        if (tunnelId == null) {
            return R.err("隧道 ID 不能为空");
        }
        List<CloudflareDnsBinding> bindings = cloudflareDnsBindingService.list(
                new QueryWrapper<CloudflareDnsBinding>()
                        .eq("tunnel_id", tunnelId)
                        .eq("status", 1));
        return syncBindings(bindings, trigger);
    }

    @Override
    public R syncBindingsByNode(Long nodeId, String trigger) {
        if (nodeId == null) {
            return R.err("节点 ID 不能为空");
        }
        List<CloudflareDnsBinding> bindings = cloudflareDnsBindingService.list(
                new QueryWrapper<CloudflareDnsBinding>().eq("status", 1));
        List<CloudflareDnsBinding> matchedBindings = bindings.stream()
                .filter(binding -> resolveNodeIds(binding).contains(nodeId))
                .collect(Collectors.toList());
        return syncBindings(matchedBindings, trigger);
    }

    @Override
    public R deleteBindingAndRecords(Long bindingId) {
        CloudflareDnsBinding binding = cloudflareDnsBindingService.getBindingById(bindingId);
        if (binding == null) {
            return R.ok();
        }

        CloudflareDnsSetting setting = cloudflareDnsSettingService.getCurrentSetting();
        String readyMessage = validateCredentials(setting);
        if (readyMessage != null) {
            cloudflareDnsBindingService.removeById(bindingId);
            return R.ok("Cloudflare 凭据不可用，已删除本地 DNS 绑定，未清理远端记录: " + readyMessage);
        }

        try {
            deleteManagedRecords(setting, binding);
            cloudflareDnsBindingService.removeById(bindingId);
            return R.ok();
        } catch (Exception e) {
            markBinding(binding, SYNC_FAILED, e.getMessage(), null);
            return R.err("删除 Cloudflare DNS 记录失败: " + e.getMessage());
        }
    }

    @Override
    public R deleteBindingRecordsByDomains(Long bindingId, List<String> domains) {
        if (bindingId == null) {
            return R.err("DNS 绑定 ID 不能为空");
        }
        List<String> normalizedDomains = normalizeDomains(domains);
        if (normalizedDomains.isEmpty()) {
            return R.ok("没有需要清理的域名");
        }

        CloudflareDnsSetting setting = cloudflareDnsSettingService.getCurrentSetting();
        String readyMessage = validateCredentials(setting);
        if (readyMessage != null) {
            return R.err(readyMessage);
        }

        CloudflareDnsBinding binding = new CloudflareDnsBinding();
        binding.setId(bindingId);
        binding.setDomain(JSON.toJSONString(normalizedDomains));
        try {
            deleteManagedRecords(setting, binding);
            return R.ok("DNS 记录已清理");
        } catch (Exception e) {
            return R.err("清理 DNS 记录失败: " + e.getMessage());
        }
    }

    @Override
    public R deleteBindingsByTunnel(Long tunnelId) {
        if (tunnelId == null) {
            return R.ok();
        }
        List<CloudflareDnsBinding> bindings = cloudflareDnsBindingService.list(
                new QueryWrapper<CloudflareDnsBinding>().eq("tunnel_id", tunnelId));
        int failed = 0;
        for (CloudflareDnsBinding binding : bindings) {
            R result = deleteBindingAndRecords(binding.getId());
            if (result.getCode() != 0) {
                failed++;
            }
        }
        return failed == 0 ? R.ok() : R.err("部分 Cloudflare DNS 绑定清理失败");
    }

    private R syncBindings(List<CloudflareDnsBinding> bindings, String trigger) {
        if (bindings == null || bindings.isEmpty()) {
            return R.ok("没有需要同步的 DNS 绑定");
        }
        int success = 0;
        int failed = 0;
        for (CloudflareDnsBinding binding : bindings) {
            R result = syncBinding(binding.getId(), trigger);
            if (result.getCode() == 0) {
                success++;
            } else {
                failed++;
            }
        }
        String message = "同步完成，成功 " + success + " 条，失败 " + failed + " 条";
        return failed == 0 ? R.ok(message) : R.err(message);
    }

    private R syncBindingInternal(CloudflareDnsSetting setting, CloudflareDnsBinding binding, String trigger) {
        try {
            List<Long> nodeIds = resolveNodeIds(binding);
            if (nodeIds.isEmpty()) {
                markBinding(binding, SYNC_FAILED, "绑定没有可用节点", null);
                return R.err("绑定没有可用节点");
            }
            List<String> domains = resolveDomains(binding);
            if (domains.isEmpty()) {
                markBinding(binding, SYNC_FAILED, "绑定没有域名", null);
                return R.err("绑定没有域名");
            }
            String primaryDomain = domains.get(0);
            List<String> aliasDomains = domains.size() > 1 ? domains.subList(1, domains.size()) : Collections.emptyList();

            String recordType = resolveRecordType(binding.getRecordType(), setting.getRecordType());
            Map<Long, NodeDnsState> nodeStates = resolveNodeStates(nodeIds, recordType);
            SmartPoolPlan smartPoolPlan = null;
            List<Long> effectiveNodeIds = nodeIds;
            if (isEnabled(binding.getSmartPoolEnabled())) {
                smartPoolPlan = selectSmartPoolNodes(binding, nodeIds, nodeStates);
                effectiveNodeIds = smartPoolPlan.activeNodeIds;
                applySmartPoolPlan(binding, smartPoolPlan);
            }

            List<CloudflareDnsTarget> desiredTargets = new ArrayList<>();
            Set<Long> unresolvedNodeIds = new HashSet<>();
            for (Long nodeId : effectiveNodeIds) {
                NodeDnsState state = nodeStates.get(nodeId);
                if (state == null || state.targets.isEmpty()) {
                    unresolvedNodeIds.add(nodeId);
                    continue;
                }
                desiredTargets.addAll(state.targets);
            }
            desiredTargets = deduplicateTargets(desiredTargets);

            if (desiredTargets.isEmpty()) {
                String message = "未解析到任何可用公网 " + recordTypeLabel(recordType) + "，已保留旧 DNS 记录";
                if (smartPoolPlan != null && smartPoolPlan.activeNodeIds.isEmpty()) {
                    message = "智能池没有可用活跃节点，已保留旧 DNS 记录";
                }
                markBinding(binding, SYNC_FAILED, message, null);
                return R.err(message);
            }

            List<CloudflareDnsRecord> primaryExistingRecords = fetchManagedRecords(setting, binding, primaryDomain);
            deleteRecordsByType(setting, primaryExistingRecords, RECORD_TYPE_CNAME);
            primaryExistingRecords = filterManagedRecords(primaryExistingRecords, record -> !RECORD_TYPE_CNAME.equalsIgnoreCase(record.getType()));

            Set<String> desiredRecordKeys = new HashSet<>();
            for (CloudflareDnsTarget target : desiredTargets) {
                desiredRecordKeys.add(targetRecordKey(primaryDomain, target));
            }
            upsertDesiredRecords(setting, binding, primaryDomain, desiredTargets, primaryExistingRecords);
            deleteStaleRecords(setting, binding, primaryExistingRecords, unresolvedNodeIds, desiredRecordKeys);

            for (String aliasDomain : aliasDomains) {
                syncAliasDomain(setting, binding, aliasDomain, primaryDomain);
            }

            String message = "DNS 同步成功，主域名 1 个，别名 " + aliasDomains.size() + " 个，主记录 " + desiredTargets.size() + " 条";
            if (smartPoolPlan != null) {
                message += "，智能池活跃 " + smartPoolPlan.activeNodeIds.size()
                        + "/" + smartPoolPlan.desiredActiveCount
                        + "，备用 " + smartPoolPlan.backupNodeIds.size()
                        + "，优先 " + smartPoolPlan.preferredCount
                        + "，排除 " + smartPoolPlan.excludedCount;
            }
            if (!unresolvedNodeIds.isEmpty()) {
                message += "，" + unresolvedNodeIds.size() + " 个活跃节点解析失败已保留旧记录";
            }
            markBinding(binding, SYNC_SUCCESS, message, desiredTargets);
            updateSettingSyncStatus(setting, SYNC_SUCCESS, "最近由 " + trigger + " 触发: " + String.join(", ", domains));
            return R.ok(message);
        } catch (Exception e) {
            log.warn("Cloudflare DNS sync failed, bindingId={}, trigger={}, error={}", binding.getId(), trigger, e.getMessage());
            markBinding(binding, SYNC_FAILED, e.getMessage(), null);
            updateSettingSyncStatus(setting, SYNC_FAILED, e.getMessage());
            return R.err("Cloudflare DNS 同步失败: " + e.getMessage());
        }
    }

    private Map<Long, NodeDnsState> resolveNodeStates(List<Long> nodeIds, String recordType) {
        Map<Long, NodeDnsState> states = new LinkedHashMap<>();
        for (Long nodeId : normalizeNodeIdList(nodeIds)) {
            Node node = nodeService.getById(nodeId);
            states.put(nodeId, new NodeDnsState(nodeId, node, resolveNodeTargets(node, recordType)));
        }
        return states;
    }

    private SmartPoolPlan selectSmartPoolNodes(CloudflareDnsBinding binding,
                                               List<Long> nodeIds,
                                               Map<Long, NodeDnsState> nodeStates) {
        long now = System.currentTimeMillis();
        List<Long> allNodeIds = normalizeNodeIdList(nodeIds);
        List<Long> preferredNodeIds = filterKnownNodeIds(
                TunnelNodeUtil.parseNodeIds(binding.getSmartPoolPreferredNodeIds()), allNodeIds);
        List<Long> previousActiveNodeIds = filterKnownNodeIds(
                TunnelNodeUtil.parseNodeIds(binding.getSmartPoolActiveNodeIds()), allNodeIds);
        int desiredActiveCount = calculateSmartPoolActiveCount(allNodeIds.size());

        List<Long> preferredActiveNodeIds = chooseSmartPoolActiveNodeIds(binding, allNodeIds, preferredNodeIds, nodeStates, desiredActiveCount, now);
        List<Long> activeNodeIds = preferredActiveNodeIds;
        if (sameLongList(previousActiveNodeIds, preferredActiveNodeIds)
                || (preferredNodeIds.isEmpty() && shouldKeepCurrentSmartPool(binding, previousActiveNodeIds, desiredActiveCount, nodeStates, now))) {
            activeNodeIds = previousActiveNodeIds;
        }

        List<Long> backupNodeIds = chooseSmartPoolBackupNodeIds(binding, allNodeIds, preferredNodeIds, nodeStates, activeNodeIds, now);
        SmartPoolPlan plan = new SmartPoolPlan();
        plan.now = now;
        plan.desiredActiveCount = desiredActiveCount;
        plan.activeNodeIds = activeNodeIds;
        plan.backupNodeIds = backupNodeIds;
        plan.preferredCount = preferredNodeIds.size();
        plan.excludedCount = Math.max(0, allNodeIds.size() - activeNodeIds.size() - backupNodeIds.size());
        return plan;
    }

    private boolean shouldKeepCurrentSmartPool(CloudflareDnsBinding binding,
                                               List<Long> currentActiveNodeIds,
                                               int desiredActiveCount,
                                               Map<Long, NodeDnsState> nodeStates,
                                               long now) {
        if (!isCurrentSmartPoolUsable(currentActiveNodeIds, desiredActiveCount, nodeStates)) {
            return false;
        }
        Long lastSwitchAt = binding.getSmartPoolLastSwitchAt();
        return lastSwitchAt != null && now - lastSwitchAt < SMART_POOL_SWITCH_COOLDOWN_MS;
    }

    private boolean isCurrentSmartPoolUsable(List<Long> currentActiveNodeIds,
                                             int desiredActiveCount,
                                             Map<Long, NodeDnsState> nodeStates) {
        int usableCount = countSmartPoolUsableNodes(nodeStates.values());
        int requiredCount = Math.min(desiredActiveCount, usableCount);
        if (requiredCount <= 0 || currentActiveNodeIds.size() != requiredCount) {
            return false;
        }
        for (Long nodeId : currentActiveNodeIds) {
            if (!isSmartPoolUsable(nodeStates.get(nodeId))) {
                return false;
            }
        }
        return true;
    }

    private int countSmartPoolUsableNodes(Collection<NodeDnsState> states) {
        int count = 0;
        for (NodeDnsState state : states) {
            if (isSmartPoolUsable(state)) {
                count++;
            }
        }
        return count;
    }

    private List<Long> chooseSmartPoolActiveNodeIds(CloudflareDnsBinding binding,
                                                    List<Long> allNodeIds,
                                                    List<Long> preferredNodeIds,
                                                    Map<Long, NodeDnsState> nodeStates,
                                                    int desiredActiveCount,
                                                    long now) {
        List<NodeDnsState> candidates = orderSmartPoolCandidates(binding, allNodeIds, preferredNodeIds, nodeStates, now);
        int limit = Math.min(desiredActiveCount, candidates.size());
        List<Long> activeNodeIds = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            activeNodeIds.add(candidates.get(i).nodeId);
        }
        return activeNodeIds;
    }

    private List<Long> chooseSmartPoolBackupNodeIds(CloudflareDnsBinding binding,
                                                    List<Long> allNodeIds,
                                                    List<Long> preferredNodeIds,
                                                    Map<Long, NodeDnsState> nodeStates,
                                                    List<Long> activeNodeIds,
                                                    long now) {
        Set<Long> activeSet = new HashSet<>(activeNodeIds);
        List<Long> backupNodeIds = new ArrayList<>();
        for (NodeDnsState state : orderSmartPoolCandidates(binding, allNodeIds, preferredNodeIds, nodeStates, now)) {
            if (!activeSet.contains(state.nodeId)) {
                backupNodeIds.add(state.nodeId);
            }
        }
        return backupNodeIds;
    }

    private List<NodeDnsState> orderSmartPoolCandidates(CloudflareDnsBinding binding,
                                                        List<Long> allNodeIds,
                                                        List<Long> preferredNodeIds,
                                                        Map<Long, NodeDnsState> nodeStates,
                                                        long now) {
        long bucket = now / SMART_POOL_ROTATE_INTERVAL_MS;
        Map<Long, Integer> preferredOrder = buildPreferredOrder(preferredNodeIds);
        List<NodeDnsState> candidates = new ArrayList<>();
        for (Long nodeId : allNodeIds) {
            NodeDnsState state = nodeStates.get(nodeId);
            if (isSmartPoolUsable(state)) {
                candidates.add(state);
            }
        }
        candidates.sort(Comparator
                .comparingInt(this::smartPoolHealthRank)
                .thenComparingInt(state -> preferredOrder.getOrDefault(state.nodeId, Integer.MAX_VALUE))
                .thenComparingLong(state -> stableHash((binding.getId() == null ? 0 : binding.getId())
                        + ":" + bucket + ":" + state.nodeId)));
        return candidates;
    }

    private boolean isSmartPoolUsable(NodeDnsState state) {
        return state != null
                && state.node != null
                && state.node.getStatus() != null
                && state.node.getStatus() == NODE_ONLINE
                && !state.targets.isEmpty()
                && !isCriticalWallStatus(state.node.getWallMonitorStatus());
    }

    private int smartPoolHealthRank(NodeDnsState state) {
        if (state == null || state.node == null) {
            return 100;
        }
        String status = normalizeWallStatus(state.node.getWallMonitorStatus());
        if (WALL_STATUS_OK.equals(status)) {
            return 0;
        }
        if (WALL_STATUS_OBSERVING.equals(status)) {
            return 2;
        }
        if (WALL_STATUS_CHECK_FAILED.equals(status)) {
            return 3;
        }
        return 1;
    }

    private boolean isCriticalWallStatus(String status) {
        String normalized = normalizeWallStatus(status);
        return WALL_STATUS_SUSPECTED_BLOCKED.equals(normalized) || WALL_STATUS_NODE_OFFLINE.equals(normalized);
    }

    private String normalizeWallStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase() : "";
    }

    private int calculateSmartPoolActiveCount(int totalNodeCount) {
        if (totalNodeCount <= 1) {
            return Math.max(0, totalNodeCount);
        }
        if (totalNodeCount == 2) {
            return 1;
        }
        if (totalNodeCount == 3) {
            return 2;
        }
        if (totalNodeCount == 4) {
            return 3;
        }
        return Math.min(totalNodeCount, Math.max(1, (int) Math.ceil(totalNodeCount * 0.70D)));
    }

    private void applySmartPoolPlan(CloudflareDnsBinding binding, SmartPoolPlan plan) {
        List<Long> previousActiveNodeIds = TunnelNodeUtil.parseNodeIds(binding.getSmartPoolActiveNodeIds());
        if (!sameLongList(previousActiveNodeIds, plan.activeNodeIds)) {
            binding.setSmartPoolLastSwitchAt(plan.now);
        }
        binding.setSmartPoolActiveNodeIds(TunnelNodeUtil.toJsonArray(plan.activeNodeIds));
        binding.setSmartPoolBackupNodeIds(TunnelNodeUtil.toJsonArray(plan.backupNodeIds));
        binding.setSmartPoolNextRotateAt(nextSmartPoolRotateAt(plan.now));
    }

    private long nextSmartPoolRotateAt(long now) {
        return ((now / SMART_POOL_ROTATE_INTERVAL_MS) + 1) * SMART_POOL_ROTATE_INTERVAL_MS;
    }

    private List<Long> normalizeNodeIdList(List<Long> rawIds) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (rawIds != null) {
            for (Long id : rawIds) {
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private List<Long> filterKnownNodeIds(List<Long> rawIds, List<Long> allowedNodeIds) {
        Set<Long> allowed = new HashSet<>(allowedNodeIds);
        List<Long> result = new ArrayList<>();
        for (Long nodeId : normalizeNodeIdList(rawIds)) {
            if (allowed.contains(nodeId)) {
                result.add(nodeId);
            }
        }
        return result;
    }

    private Map<Long, Integer> buildPreferredOrder(List<Long> preferredNodeIds) {
        Map<Long, Integer> order = new HashMap<>();
        int index = 0;
        for (Long nodeId : normalizeNodeIdList(preferredNodeIds)) {
            order.putIfAbsent(nodeId, index++);
        }
        return order;
    }

    private boolean sameLongList(List<Long> left, List<Long> right) {
        return Objects.equals(normalizeNodeIdList(left), normalizeNodeIdList(right));
    }

    private long stableHash(String value) {
        long hash = 1125899906842597L;
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            hash = 31 * hash + text.charAt(i);
        }
        return hash & Long.MAX_VALUE;
    }

    private void upsertDesiredRecords(CloudflareDnsSetting setting,
                                      CloudflareDnsBinding binding,
                                      String primaryDomain,
                                      List<CloudflareDnsTarget> desiredTargets,
                                      List<CloudflareDnsRecord> existingRecords) {
        Map<String, CloudflareDnsRecord> existingByTarget = existingRecords.stream()
                .collect(Collectors.toMap(this::recordContentKey, record -> record, (left, right) -> left));

        for (CloudflareDnsTarget target : desiredTargets) {
            CloudflareDnsRecord existing = existingByTarget.get(targetRecordKey(primaryDomain, target));
            CloudflareDnsRecord desiredRecord = buildDesiredRecord(setting, binding, primaryDomain, target);
            if (existing == null) {
                cloudflareApiClient.createDnsRecord(setting.getZoneId(), setting.getApiToken(), desiredRecord);
                continue;
            }
            if (shouldUpdateRecord(existing, desiredRecord)) {
                cloudflareApiClient.updateDnsRecord(setting.getZoneId(), setting.getApiToken(), existing.getId(), desiredRecord);
            }
        }
    }

    private void syncAliasDomain(CloudflareDnsSetting setting,
                                 CloudflareDnsBinding binding,
                                 String aliasDomain,
                                 String primaryDomain) {
        List<CloudflareDnsRecord> aliasRecords = fetchManagedRecords(setting, binding, aliasDomain);
        CloudflareDnsRecord desiredRecord = buildAliasRecord(setting, binding, aliasDomain, primaryDomain);
        CloudflareDnsRecord existingCname = aliasRecords.stream()
                .filter(record -> RECORD_TYPE_CNAME.equalsIgnoreCase(record.getType()))
                .findFirst()
                .orElse(null);
        boolean hasConflict = aliasRecords.stream()
                .anyMatch(record -> !RECORD_TYPE_CNAME.equalsIgnoreCase(record.getType()));

        if (!hasConflict && existingCname != null) {
            if (shouldUpdateRecord(existingCname, desiredRecord)) {
                cloudflareApiClient.updateDnsRecord(setting.getZoneId(), setting.getApiToken(), existingCname.getId(), desiredRecord);
            }
            return;
        }

        if (!aliasRecords.isEmpty()) {
            deleteManagedRecords(setting, aliasRecords);
        }
        cloudflareApiClient.createDnsRecord(setting.getZoneId(), setting.getApiToken(), desiredRecord);
    }

    private void deleteStaleRecords(CloudflareDnsSetting setting,
                                    CloudflareDnsBinding binding,
                                    List<CloudflareDnsRecord> existingRecords,
                                    Set<Long> unresolvedNodeIds,
                                    Set<String> desiredRecordKeys) {
        for (CloudflareDnsRecord record : existingRecords) {
            Long nodeId = extractCommentLong(record.getComment(), "node");
            if (nodeId != null && unresolvedNodeIds.contains(nodeId)) {
                continue;
            }
            if (desiredRecordKeys.contains(recordContentKey(record))) {
                continue;
            }
            cloudflareApiClient.deleteDnsRecord(setting.getZoneId(), setting.getApiToken(), record.getId());
        }
    }

    private void deleteRecordsByType(CloudflareDnsSetting setting,
                                     List<CloudflareDnsRecord> records,
                                     String recordType) {
        for (CloudflareDnsRecord record : records) {
            if (record != null && recordType.equalsIgnoreCase(record.getType())) {
                cloudflareApiClient.deleteDnsRecord(setting.getZoneId(), setting.getApiToken(), record.getId());
            }
        }
    }

    private CloudflareDnsRecord buildDesiredRecord(CloudflareDnsSetting setting,
                                                   CloudflareDnsBinding binding,
                                                   String domain,
                                                   CloudflareDnsTarget target) {
        CloudflareDnsRecord record = new CloudflareDnsRecord();
        record.setName(domain);
        record.setType(target.getRecordType());
        record.setContent(target.getContent());
        record.setTtl(resolveTtl(setting.getTtl()));
        record.setProxied(false);
        record.setComment(buildComment(binding, target.getNodeId(), target.getRecordType()));
        return record;
    }

    private CloudflareDnsRecord buildAliasRecord(CloudflareDnsSetting setting,
                                                 CloudflareDnsBinding binding,
                                                 String aliasDomain,
                                                 String primaryDomain) {
        CloudflareDnsRecord record = new CloudflareDnsRecord();
        record.setName(aliasDomain);
        record.setType(RECORD_TYPE_CNAME);
        record.setContent(primaryDomain);
        record.setTtl(resolveTtl(setting.getTtl()));
        record.setProxied(false);
        record.setComment(buildComment(binding, null, RECORD_TYPE_CNAME));
        return record;
    }

    private List<CloudflareDnsRecord> fetchManagedRecords(CloudflareDnsSetting setting, CloudflareDnsBinding binding) {
        return fetchManagedRecords(setting, binding, resolveDomains(binding));
    }

    private List<CloudflareDnsRecord> fetchManagedRecords(CloudflareDnsSetting setting,
                                                          CloudflareDnsBinding binding,
                                                          String domain) {
        return fetchManagedRecords(setting, binding, Collections.singletonList(domain));
    }

    private List<CloudflareDnsRecord> fetchManagedRecords(CloudflareDnsSetting setting,
                                                          CloudflareDnsBinding binding,
                                                          List<String> domains) {
        List<CloudflareDnsRecord> records = new ArrayList<>();
        for (String domain : domains) {
            for (String type : MANAGED_RECORD_TYPES) {
                records.addAll(cloudflareApiClient.listDnsRecords(setting.getZoneId(), setting.getApiToken(), domain, type)
                        .stream()
                        .filter(record -> isManagedRecord(binding, record))
                        .collect(Collectors.toList()));
            }
        }
        return records;
    }

    private void deleteManagedRecords(CloudflareDnsSetting setting, CloudflareDnsBinding binding) {
        for (CloudflareDnsRecord record : fetchManagedRecords(setting, binding)) {
            cloudflareApiClient.deleteDnsRecord(setting.getZoneId(), setting.getApiToken(), record.getId());
        }
    }

    private void deleteManagedRecords(CloudflareDnsSetting setting, List<CloudflareDnsRecord> records) {
        for (CloudflareDnsRecord record : records) {
            cloudflareApiClient.deleteDnsRecord(setting.getZoneId(), setting.getApiToken(), record.getId());
        }
    }

    private List<CloudflareDnsRecord> filterManagedRecords(List<CloudflareDnsRecord> records,
                                                           java.util.function.Predicate<CloudflareDnsRecord> predicate) {
        if (records == null || records.isEmpty()) {
            return new ArrayList<>();
        }
        return records.stream().filter(predicate).collect(Collectors.toList());
    }

    private List<Long> resolveNodeIds(CloudflareDnsBinding binding) {
        if (binding == null) {
            return new ArrayList<>();
        }
        if (binding.getUseTunnelNodes() == null || binding.getUseTunnelNodes() == 1) {
            Tunnel tunnel = tunnelService.getById(binding.getTunnelId());
            return TunnelNodeUtil.getInNodeIds(tunnel);
        }
        return TunnelNodeUtil.parseNodeIds(binding.getNodeIds());
    }

    private List<String> resolveDomains(CloudflareDnsBinding binding) {
        if (binding == null || !StringUtils.hasText(binding.getDomain())) {
            return new ArrayList<>();
        }
        try {
            List<String> domains = JSON.parseArray(binding.getDomain(), String.class);
            if (domains != null) {
                return normalizeDomains(domains);
            }
        } catch (Exception ignored) {
            // Legacy single-domain storage.
        }
        return normalizeDomains(Collections.singletonList(binding.getDomain()));
    }

    private List<String> normalizeDomains(List<String> rawDomains) {
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        if (rawDomains != null) {
            for (String rawDomain : rawDomains) {
                if (!StringUtils.hasText(rawDomain)) {
                    continue;
                }
                String domain = rawDomain.trim().toLowerCase(Locale.ROOT);
                while (domain.endsWith(".")) {
                    domain = domain.substring(0, domain.length() - 1);
                }
                if (!domain.isEmpty()) {
                    domains.add(domain);
                }
            }
        }
        return new ArrayList<>(domains);
    }

    private List<CloudflareDnsTarget> resolveNodeTargets(Node node, String recordType) {
        if (node == null) {
            return new ArrayList<>();
        }

        LinkedHashSet<String> targets = new LinkedHashSet<>();
        if (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_A.equals(recordType)) {
            collectAddressTargets(node, targets, node.getServerIpv4(), RECORD_TYPE_A);
        }
        if (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_AAAA.equals(recordType)) {
            collectAddressTargets(node, targets, node.getServerIpv6(), RECORD_TYPE_AAAA);
        }
        collectAddressTargets(node, targets, node.getServerIp(), recordType);
        return toTargets(node.getId(), targets);
    }

    private void collectAddressTargets(Node node, LinkedHashSet<String> targets, String value, String recordType) {
        if (!StringUtils.hasText(value)) {
            return;
        }

        String host = normalizeHost(value);
        try {
            if (isIpv4Literal(host)) {
                if (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_A.equals(recordType)) {
                    addPublicTarget(targets, RECORD_TYPE_A, InetAddress.getByName(host));
                }
                return;
            }
            if (isIpv6Literal(host)) {
                if (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_AAAA.equals(recordType)) {
                    addPublicTarget(targets, RECORD_TYPE_AAAA, InetAddress.getByName(host));
                }
                return;
            }

            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address && (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_A.equals(recordType))) {
                    addPublicTarget(targets, RECORD_TYPE_A, address);
                } else if (address instanceof Inet6Address && (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_AAAA.equals(recordType))) {
                    addPublicTarget(targets, RECORD_TYPE_AAAA, address);
                }
            }
        } catch (Exception e) {
            log.warn("Resolve node target failed, nodeId={}, host={}, error={}", node.getId(), host, e.getMessage());
        }
    }

    private void addPublicTarget(LinkedHashSet<String> targets, String recordType, InetAddress address) {
        if (!isPublicAddress(address)) {
            return;
        }
        targets.add(recordType + "|" + stripIpv6Scope(address.getHostAddress()));
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
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
    }

    private List<CloudflareDnsTarget> deduplicateTargets(List<CloudflareDnsTarget> targets) {
        Map<String, CloudflareDnsTarget> unique = new LinkedHashMap<>();
        for (CloudflareDnsTarget target : targets) {
            unique.putIfAbsent(target.getRecordType() + "|" + target.getContent(), target);
        }
        return new ArrayList<>(unique.values());
    }

    private List<CloudflareDnsTarget> toTargets(Long nodeId, LinkedHashSet<String> rawTargets) {
        List<CloudflareDnsTarget> targets = new ArrayList<>();
        for (String rawTarget : rawTargets) {
            String[] parts = rawTarget.split("\\|", 2);
            if (parts.length == 2) {
                targets.add(new CloudflareDnsTarget(nodeId, parts[0], parts[1]));
            }
        }
        return targets;
    }

    private String validateSetting(CloudflareDnsSetting setting) {
        if (setting == null || !isEnabled(setting.getEnabled())) {
            return "Cloudflare DNS 未启用";
        }
        return validateCredentials(setting);
    }

    private String validateCredentials(CloudflareDnsSetting setting) {
        if (setting == null) {
            return "Cloudflare 配置不存在";
        }
        if (!StringUtils.hasText(setting.getApiToken())) {
            return "Cloudflare API Token 未配置";
        }
        if (!StringUtils.hasText(setting.getZoneId())) {
            return "Cloudflare Zone ID 未配置";
        }
        return null;
    }

    private void markBinding(CloudflareDnsBinding binding, String status, String message, List<CloudflareDnsTarget> targets) {
        binding.setLastSyncAt(System.currentTimeMillis());
        binding.setLastSyncStatus(status);
        binding.setLastSyncMessage(trimMessage(message));
        if (targets != null) {
            binding.setLastResolvedIps(JSON.toJSONString(targets));
        }
        cloudflareDnsBindingService.updateById(binding);
    }

    private void updateSettingSyncStatus(CloudflareDnsSetting setting, String status, String message) {
        setting.setLastSyncAt(System.currentTimeMillis());
        setting.setLastSyncStatus(status);
        setting.setLastSyncMessage(trimMessage(message));
        setting.setUpdatedTime(System.currentTimeMillis());
        cloudflareDnsSettingService.updateById(setting);
    }

    private String trimMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private String resolveRecordType(String bindingRecordType, String settingRecordType) {
        if (isSupportedRecordType(bindingRecordType)) {
            return bindingRecordType.trim().toUpperCase();
        }
        if (isSupportedRecordType(settingRecordType)) {
            return settingRecordType.trim().toUpperCase();
        }
        return RECORD_TYPE_AUTO;
    }

    private boolean isSupportedRecordType(String recordType) {
        if (!StringUtils.hasText(recordType)) {
            return false;
        }
        String normalized = recordType.trim().toUpperCase();
        return RECORD_TYPE_AUTO.equals(normalized) || RECORD_TYPE_A.equals(normalized) || RECORD_TYPE_AAAA.equals(normalized);
    }

    private String recordTypeLabel(String recordType) {
        if (RECORD_TYPE_A.equals(recordType)) {
            return "IPv4";
        }
        if (RECORD_TYPE_AAAA.equals(recordType)) {
            return "IPv6";
        }
        return "IPv4/IPv6";
    }

    private int resolveTtl(Integer ttl) {
        return ttl == null || ttl <= 0 ? 1 : ttl;
    }

    private int resolveInterval(Integer intervalSeconds) {
        return intervalSeconds == null || intervalSeconds <= 0 ? 120 : intervalSeconds;
    }

    private boolean isEnabled(Integer value) {
        return value != null && value == 1;
    }

    private boolean shouldUpdateRecord(CloudflareDnsRecord existing, CloudflareDnsRecord desired) {
        return !Objects.equals(existing.getName(), desired.getName())
                || !Objects.equals(existing.getType(), desired.getType())
                || !Objects.equals(existing.getContent(), desired.getContent())
                || !Objects.equals(existing.getTtl(), desired.getTtl())
                || !Objects.equals(existing.getProxied(), desired.getProxied())
                || !Objects.equals(existing.getComment(), desired.getComment());
    }

    private String buildComment(CloudflareDnsBinding binding, Long nodeId, String type) {
        StringBuilder builder = new StringBuilder(MANAGED_COMMENT_PREFIX)
                .append("|binding=")
                .append(binding.getId());
        if (nodeId != null) {
            builder.append("|node=").append(nodeId);
        }
        builder.append("|type=").append(type);
        return builder.toString();
    }

    private boolean isManagedRecord(CloudflareDnsBinding binding, CloudflareDnsRecord record) {
        return record != null
                && StringUtils.hasText(record.getComment())
                && record.getComment().startsWith(MANAGED_COMMENT_PREFIX + "|binding=" + binding.getId() + "|");
    }

    private String recordContentKey(CloudflareDnsRecord record) {
        String name = record.getName() == null ? "" : record.getName().toLowerCase(Locale.ROOT);
        String type = record.getType() == null ? "" : record.getType().toUpperCase();
        String content = record.getContent() == null ? "" : record.getContent();
        return name + "|" + type + "|" + content;
    }

    private String targetRecordKey(String domain, CloudflareDnsTarget target) {
        String normalizedDomain = domain == null ? "" : domain.toLowerCase(Locale.ROOT);
        return normalizedDomain + "|" + target.getRecordType() + "|" + target.getContent();
    }

    private Long extractCommentLong(String comment, String key) {
        if (!StringUtils.hasText(comment)) {
            return null;
        }
        String marker = key + "=";
        for (String part : comment.split("\\|")) {
            if (part.startsWith(marker)) {
                try {
                    return Long.valueOf(part.substring(marker.length()));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isIpv4Literal(String value) {
        return value != null && value.matches("^(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})(\\.(25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}$");
    }

    private boolean isIpv6Literal(String value) {
        if (value == null || !value.contains(":") || !value.matches("^[0-9a-fA-F:.%]+$")) {
            return false;
        }
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String stripIpv6Scope(String value) {
        int scopeIndex = value.indexOf('%');
        return scopeIndex >= 0 ? value.substring(0, scopeIndex) : value;
    }

    private static class NodeDnsState {
        private final Long nodeId;
        private final Node node;
        private final List<CloudflareDnsTarget> targets;

        private NodeDnsState(Long nodeId, Node node, List<CloudflareDnsTarget> targets) {
            this.nodeId = nodeId;
            this.node = node;
            this.targets = targets == null ? new ArrayList<>() : targets;
        }
    }

    private static class SmartPoolPlan {
        private long now;
        private int desiredActiveCount;
        private int preferredCount;
        private List<Long> activeNodeIds = new ArrayList<>();
        private List<Long> backupNodeIds = new ArrayList<>();
        private int excludedCount;
    }
}
