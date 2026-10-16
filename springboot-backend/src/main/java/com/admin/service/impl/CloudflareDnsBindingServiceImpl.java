package com.admin.service.impl;

import com.admin.common.dto.CloudflareDnsBindingDto;
import com.admin.common.lang.R;
import com.admin.common.utils.TunnelNodeUtil;
import com.admin.entity.CloudflareDnsBinding;
import com.admin.mapper.CloudflareDnsBindingMapper;
import com.admin.service.CloudflareDnsBindingService;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

@Service
public class CloudflareDnsBindingServiceImpl extends ServiceImpl<CloudflareDnsBindingMapper, CloudflareDnsBinding> implements CloudflareDnsBindingService {

    private static final String DEFAULT_RECORD_TYPE = "AUTO";

    @Override
    public R getBindingList() {
        return R.ok(this.list(new QueryWrapper<CloudflareDnsBinding>().orderByDesc("id")));
    }

    @Override
    public R saveBinding(CloudflareDnsBindingDto dto) {
        if (dto == null) {
            return R.err("DNS 绑定不能为空");
        }
        if (dto.getTunnelId() == null || dto.getTunnelId() <= 0) {
            return R.err("请选择隧道");
        }

        Integer useTunnelNodes = dto.getUseTunnelNodes() == null ? 1 : (dto.getUseTunnelNodes() == 1 ? 1 : 0);
        List<Long> nodeIds = normalizeNodeIds(dto.getNodeIds());
        List<Long> preferredNodeIds = normalizeNodeIds(dto.getSmartPoolPreferredNodeIds());
        List<List<Long>> rotationGroups;
        try {
            rotationGroups = normalizeRotationGroups(dto.getRotationGroups());
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        boolean rotationEnabled = dto.getRotationEnabled() != null && dto.getRotationEnabled() == 1;
        boolean supplementEnabled = dto.getSupplementEnabled() != null && dto.getSupplementEnabled() == 1;
        int supplementTargetCount = dto.getSupplementTargetCount() == null ? 1 : Math.max(dto.getSupplementTargetCount(), 1);
        if (useTunnelNodes == 0 && nodeIds.isEmpty()) {
            return R.err("手动模式下请选择至少一个节点");
        }
        if (rotationEnabled) {
            if (rotationGroups.size() < 2) {
                return R.err("分组轮训至少需要配置两个节点组");
            }
            List<Long> allowedNodeIds = useTunnelNodes == 0 ? nodeIds : new ArrayList<>();
            if (useTunnelNodes == 0) {
                Set<Long> allowed = new HashSet<>(allowedNodeIds);
                for (List<Long> group : rotationGroups) {
                    for (Long nodeId : group) {
                        if (!allowed.contains(nodeId)) {
                            return R.err("轮训分组包含绑定范围外的节点");
                        }
                    }
                }
            }
        }

        List<String> domains = normalizeDomains(dto.getDomains());
        if (domains.isEmpty()) {
            domains = parseDomains(dto.getDomain());
        }
        if (domains.isEmpty()) {
            return R.err("请至少填写一个域名");
        }
        for (String domain : domains) {
            if (!isValidDomain(domain)) {
                return R.err("域名格式不正确: " + domain);
            }
        }
        R duplicateCheck = checkDuplicate(dto.getId(), domains);
        if (duplicateCheck.getCode() != 0) {
            return duplicateCheck;
        }

        CloudflareDnsBinding binding = dto.getId() == null ? new CloudflareDnsBinding() : this.getById(dto.getId());
        if (binding == null) {
            return R.err("DNS 绑定不存在");
        }

        binding.setTunnelId(dto.getTunnelId());
        binding.setDomain(JSON.toJSONString(domains));
        binding.setUseTunnelNodes(useTunnelNodes);
        binding.setNodeIds(useTunnelNodes == 1 ? null : TunnelNodeUtil.toJsonArray(nodeIds));
        binding.setRecordType(resolveRecordType(dto.getRecordType()));
        binding.setSmartPoolEnabled(dto.getSmartPoolEnabled() != null && dto.getSmartPoolEnabled() == 1 ? 1 : 0);
        binding.setSmartPoolPreferredNodeIds(TunnelNodeUtil.toJsonArray(preferredNodeIds));
        binding.setRotationEnabled(rotationEnabled ? 1 : 0);
        binding.setRotationGroups(rotationEnabled ? JSON.toJSONString(rotationGroups) : null);
        binding.setRotationIntervalMinutes(rotationEnabled ? Math.max(dto.getRotationIntervalMinutes() == null ? 5 : dto.getRotationIntervalMinutes(), 5) : null);
        binding.setSupplementEnabled(supplementEnabled ? 1 : 0);
        binding.setSupplementTargetCount(supplementEnabled ? Math.min(supplementTargetCount, 100) : null);
        if (!rotationEnabled) {
            binding.setRotationCurrentGroupIndex(null);
            binding.setRotationLastSwitchAt(null);
            binding.setRotationNextSwitchAt(null);
        } else if (binding.getRotationCurrentGroupIndex() == null || binding.getRotationCurrentGroupIndex() >= rotationGroups.size()) {
            binding.setRotationCurrentGroupIndex(0);
            binding.setRotationLastSwitchAt(null);
            binding.setRotationNextSwitchAt(System.currentTimeMillis() + binding.getRotationIntervalMinutes() * 60_000L);
        }
        if (binding.getSmartPoolEnabled() == 0) {
            binding.setSmartPoolActiveNodeIds(null);
            binding.setSmartPoolBackupNodeIds(null);
            binding.setSmartPoolLastSwitchAt(null);
            binding.setSmartPoolNextRotateAt(null);
        }
        binding.setStatus(1);
        binding.setUpdatedTime(System.currentTimeMillis());
        if (binding.getCreatedTime() == null) {
            binding.setCreatedTime(System.currentTimeMillis());
        }

        boolean saved = dto.getId() == null ? this.save(binding) : this.updateById(binding);
        return saved ? R.ok(binding) : R.err("DNS 绑定保存失败");
    }

    @Override
    public R deleteBinding(Long id) {
        if (id == null || id <= 0) {
            return R.err("DNS 绑定 ID 无效");
        }
        return this.removeById(id) ? R.ok() : R.err("DNS 绑定删除失败");
    }

    @Override
    public CloudflareDnsBinding getBindingById(Long id) {
        return id == null ? null : this.getById(id);
    }

    private R checkDuplicate(Long id, List<String> domains) {
        List<CloudflareDnsBinding> bindings = this.list();
        for (CloudflareDnsBinding binding : bindings) {
            if (id != null && id.equals(binding.getId())) {
                continue;
            }
            List<String> existingDomains = parseDomains(binding.getDomain());
            for (String domain : domains) {
                if (existingDomains.contains(domain)) {
                    return R.err("该域名已经绑定了 DNS 同步: " + domain);
                }
            }
        }
        return R.ok();
    }

    private List<List<Long>> normalizeRotationGroups(List<List<Long>> rawGroups) {
        List<List<Long>> groups = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        if (rawGroups == null) {
            return groups;
        }
        for (List<Long> rawGroup : rawGroups) {
            List<Long> group = normalizeNodeIds(rawGroup);
            if (group.isEmpty()) {
                continue;
            }
            for (Long nodeId : group) {
                if (!seen.add(nodeId)) {
                    throw new IllegalArgumentException("轮训节点不能重复出现在多个分组");
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private List<Long> normalizeNodeIds(List<Long> rawIds) {
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

    private List<String> parseDomains(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new ArrayList<>();
        }
        try {
            List<String> domains = JSON.parseArray(raw, String.class);
            if (domains != null) {
                return normalizeDomains(domains);
            }
        } catch (Exception ignored) {
            // Fall back to the legacy single-domain format.
        }
        return normalizeDomains(List.of(raw));
    }

    private List<String> normalizeDomains(List<String> rawDomains) {
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        if (rawDomains != null) {
            for (String rawDomain : rawDomains) {
                if (!StringUtils.hasText(rawDomain)) {
                    continue;
                }
                String normalized = rawDomain.trim().toLowerCase(Locale.ROOT);
                while (normalized.endsWith(".")) {
                    normalized = normalized.substring(0, normalized.length() - 1);
                }
                if (!normalized.isEmpty()) {
                    domains.add(normalized);
                }
            }
        }
        return new ArrayList<>(domains);
    }

    private boolean isValidDomain(String domain) {
        if (!StringUtils.hasText(domain) || domain.length() > 253) {
            return false;
        }
        String value = domain.startsWith("*.") ? domain.substring(2) : domain;
        String label = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
        return value.matches(label + "(?:\\." + label + ")+");
    }

    private String resolveRecordType(String recordType) {
        if (!StringUtils.hasText(recordType)) {
            return DEFAULT_RECORD_TYPE;
        }
        String normalized = recordType.trim().toUpperCase();
        if ("AUTO".equals(normalized) || "A".equals(normalized) || "AAAA".equals(normalized)) {
            return normalized;
        }
        return DEFAULT_RECORD_TYPE;
    }
}
