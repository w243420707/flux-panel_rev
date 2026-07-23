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
    private static final String SYNC_SUCCESS = "SUCCESS";
    private static final String SYNC_FAILED = "FAILED";
    private static final String SYNC_SKIPPED = "SKIPPED";
    private static final String MANAGED_COMMENT_PREFIX = "flux-panel_rev";
    private static final List<String> MANAGED_RECORD_TYPES = Arrays.asList(RECORD_TYPE_A, RECORD_TYPE_AAAA);

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

            String recordType = resolveRecordType(binding.getRecordType(), setting.getRecordType());
            List<CloudflareDnsTarget> desiredTargets = new ArrayList<>();
            Set<Long> unresolvedNodeIds = new HashSet<>();
            for (Long nodeId : nodeIds) {
                Node node = nodeService.getById(nodeId);
                List<CloudflareDnsTarget> nodeTargets = resolveNodeTargets(node, recordType);
                if (nodeTargets.isEmpty()) {
                    unresolvedNodeIds.add(nodeId);
                    continue;
                }
                desiredTargets.addAll(nodeTargets);
            }
            desiredTargets = deduplicateTargets(desiredTargets);

            if (desiredTargets.isEmpty()) {
                String message = "未解析到任何可用公网 IP，已保留旧 DNS 记录";
                markBinding(binding, SYNC_FAILED, message, null);
                return R.err(message);
            }

            List<CloudflareDnsRecord> existingRecords = fetchManagedRecords(setting, binding);
            Set<String> desiredRecordKeys = desiredTargets.stream()
                    .map(this::targetContentKey)
                    .collect(Collectors.toSet());
            upsertDesiredRecords(setting, binding, desiredTargets, existingRecords);
            deleteStaleRecords(setting, binding, existingRecords, unresolvedNodeIds, desiredRecordKeys);

            String message = "DNS 同步成功，目标记录 " + desiredTargets.size() + " 条";
            if (!unresolvedNodeIds.isEmpty()) {
                message += "，" + unresolvedNodeIds.size() + " 个节点解析失败已保留旧记录";
            }
            markBinding(binding, SYNC_SUCCESS, message, desiredTargets);
            updateSettingSyncStatus(setting, SYNC_SUCCESS, "最近由 " + trigger + " 触发: " + binding.getDomain());
            return R.ok(message);
        } catch (Exception e) {
            log.warn("Cloudflare DNS sync failed, bindingId={}, trigger={}, error={}", binding.getId(), trigger, e.getMessage());
            markBinding(binding, SYNC_FAILED, e.getMessage(), null);
            updateSettingSyncStatus(setting, SYNC_FAILED, e.getMessage());
            return R.err("Cloudflare DNS 同步失败: " + e.getMessage());
        }
    }

    private void upsertDesiredRecords(CloudflareDnsSetting setting,
                                      CloudflareDnsBinding binding,
                                      List<CloudflareDnsTarget> desiredTargets,
                                      List<CloudflareDnsRecord> existingRecords) {
        Map<String, CloudflareDnsRecord> existingByTarget = existingRecords.stream()
                .collect(Collectors.toMap(this::recordContentKey, record -> record, (left, right) -> left));

        for (CloudflareDnsTarget target : desiredTargets) {
            CloudflareDnsRecord existing = existingByTarget.get(targetContentKey(target));
            CloudflareDnsRecord desiredRecord = buildDesiredRecord(setting, binding, target);
            if (existing == null) {
                cloudflareApiClient.createDnsRecord(setting.getZoneId(), setting.getApiToken(), desiredRecord);
                continue;
            }
            if (shouldUpdateRecord(existing, desiredRecord)) {
                cloudflareApiClient.updateDnsRecord(setting.getZoneId(), setting.getApiToken(), existing.getId(), desiredRecord);
            }
        }
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

    private CloudflareDnsRecord buildDesiredRecord(CloudflareDnsSetting setting, CloudflareDnsBinding binding, CloudflareDnsTarget target) {
        CloudflareDnsRecord record = new CloudflareDnsRecord();
        record.setName(binding.getDomain());
        record.setType(target.getRecordType());
        record.setContent(target.getContent());
        record.setTtl(resolveTtl(setting.getTtl()));
        record.setProxied(false);
        record.setComment(buildComment(binding, target.getNodeId(), target.getRecordType()));
        return record;
    }

    private List<CloudflareDnsRecord> fetchManagedRecords(CloudflareDnsSetting setting, CloudflareDnsBinding binding) {
        List<CloudflareDnsRecord> records = new ArrayList<>();
        for (String type : MANAGED_RECORD_TYPES) {
            records.addAll(cloudflareApiClient.listDnsRecords(setting.getZoneId(), setting.getApiToken(), binding.getDomain(), type)
                    .stream()
                    .filter(record -> isManagedRecord(binding, record))
                    .collect(Collectors.toList()));
        }
        return records;
    }

    private void deleteManagedRecords(CloudflareDnsSetting setting, CloudflareDnsBinding binding) {
        for (CloudflareDnsRecord record : fetchManagedRecords(setting, binding)) {
            cloudflareApiClient.deleteDnsRecord(setting.getZoneId(), setting.getApiToken(), record.getId());
        }
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

    private List<CloudflareDnsTarget> resolveNodeTargets(Node node, String recordType) {
        if (node == null || !StringUtils.hasText(node.getServerIp())) {
            return new ArrayList<>();
        }

        String host = normalizeHost(node.getServerIp());
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        try {
            if (isIpv4Literal(host)) {
                if (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_A.equals(recordType)) {
                    addPublicTarget(targets, RECORD_TYPE_A, InetAddress.getByName(host));
                }
                return toTargets(node.getId(), targets);
            }
            if (isIpv6Literal(host)) {
                if (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_AAAA.equals(recordType)) {
                    addPublicTarget(targets, RECORD_TYPE_AAAA, InetAddress.getByName(host));
                }
                return toTargets(node.getId(), targets);
            }

            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address && (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_A.equals(recordType))) {
                    addPublicTarget(targets, RECORD_TYPE_A, address);
                } else if (address instanceof Inet6Address && (RECORD_TYPE_AUTO.equals(recordType) || RECORD_TYPE_AAAA.equals(recordType))) {
                    addPublicTarget(targets, RECORD_TYPE_AAAA, address);
                }
            }
            return toTargets(node.getId(), targets);
        } catch (Exception e) {
            log.warn("Resolve node target failed, nodeId={}, host={}, error={}", node.getId(), host, e.getMessage());
            return new ArrayList<>();
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
        return MANAGED_COMMENT_PREFIX + "|binding=" + binding.getId() + "|node=" + nodeId + "|type=" + type;
    }

    private boolean isManagedRecord(CloudflareDnsBinding binding, CloudflareDnsRecord record) {
        return record != null
                && StringUtils.hasText(record.getComment())
                && record.getComment().startsWith(MANAGED_COMMENT_PREFIX + "|binding=" + binding.getId() + "|");
    }

    private String recordContentKey(CloudflareDnsRecord record) {
        String type = record.getType() == null ? "" : record.getType().toUpperCase();
        String content = record.getContent() == null ? "" : record.getContent();
        return type + "|" + content;
    }

    private String targetContentKey(CloudflareDnsTarget target) {
        return target.getRecordType() + "|" + target.getContent();
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
}
