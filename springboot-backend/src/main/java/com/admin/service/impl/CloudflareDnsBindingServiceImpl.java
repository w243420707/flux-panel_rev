package com.admin.service.impl;

import com.admin.common.dto.CloudflareDnsBindingDto;
import com.admin.common.lang.R;
import com.admin.common.utils.TunnelNodeUtil;
import com.admin.entity.CloudflareDnsBinding;
import com.admin.mapper.CloudflareDnsBindingMapper;
import com.admin.service.CloudflareDnsBindingService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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
        if (!StringUtils.hasText(dto.getDomain())) {
            return R.err("请输入需要同步的域名");
        }

        Integer useTunnelNodes = dto.getUseTunnelNodes() == null ? 1 : (dto.getUseTunnelNodes() == 1 ? 1 : 0);
        List<Long> nodeIds = normalizeNodeIds(dto.getNodeIds());
        if (useTunnelNodes == 0 && nodeIds.isEmpty()) {
            return R.err("手动模式下请选择至少一个节点");
        }

        String domain = normalizeDomain(dto.getDomain());
        if (!isValidDomain(domain)) {
            return R.err("域名格式不正确");
        }
        R duplicateCheck = checkDuplicate(dto.getId(), domain);
        if (duplicateCheck.getCode() != 0) {
            return duplicateCheck;
        }

        CloudflareDnsBinding binding = dto.getId() == null ? new CloudflareDnsBinding() : this.getById(dto.getId());
        if (binding == null) {
            return R.err("DNS 绑定不存在");
        }

        binding.setTunnelId(dto.getTunnelId());
        binding.setDomain(domain);
        binding.setUseTunnelNodes(useTunnelNodes);
        binding.setNodeIds(useTunnelNodes == 1 ? null : TunnelNodeUtil.toJsonArray(nodeIds));
        binding.setRecordType(resolveRecordType(dto.getRecordType()));
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

    private R checkDuplicate(Long id, String domain) {
        QueryWrapper<CloudflareDnsBinding> wrapper = new QueryWrapper<CloudflareDnsBinding>()
                .eq("domain", domain);
        if (id != null) {
            wrapper.ne("id", id);
        }
        return this.count(wrapper) > 0 ? R.err("该域名已经绑定了 DNS 同步") : R.ok();
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

    private String normalizeDomain(String domain) {
        String normalized = domain.trim().toLowerCase();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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
