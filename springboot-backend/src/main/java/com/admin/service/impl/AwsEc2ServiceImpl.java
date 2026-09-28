package com.admin.service.impl;

import com.admin.common.dto.AwsEc2ActionDto;
import com.admin.common.dto.AwsEc2ChangeIpDto;
import com.admin.common.dto.AwsEc2CreateDto;
import com.admin.common.dto.AwsEc2ListDto;
import com.admin.common.dto.AwsEc2ProfileSaveDto;
import com.admin.common.dto.AwsEc2TemplateIdDto;
import com.admin.common.dto.AwsEc2TemplateSaveDto;
import com.admin.common.dto.AwsFailoverConfigDto;
import com.admin.common.dto.AwsShareGroupIdDto;
import com.admin.common.dto.AwsShareGroupSaveDto;
import com.admin.common.lang.R;
import com.admin.entity.AwsAccount;
import com.admin.entity.AwsEc2InstanceProfile;
import com.admin.entity.AwsEc2InstanceTemplate;
import com.admin.entity.AwsNodeBinding;
import com.admin.entity.AwsFailoverConfig;
import com.admin.entity.AwsShareGroup;
import com.admin.mapper.AwsAccountMapper;
import com.admin.mapper.AwsEc2InstanceProfileMapper;
import com.admin.mapper.AwsEc2InstanceTemplateMapper;
import com.admin.mapper.AwsNodeBindingMapper;
import com.admin.mapper.AwsFailoverConfigMapper;
import com.admin.mapper.AwsShareGroupMapper;
import com.admin.service.AwsEc2Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;

@Service
public class AwsEc2ServiceImpl implements AwsEc2Service {
    @Resource
    private AwsAccountMapper accountMapper;

    @Resource
    private AwsEc2InstanceProfileMapper profileMapper;

    @Resource
    private AwsEc2InstanceTemplateMapper templateMapper;

    @Resource
    private AwsNodeBindingMapper bindingMapper;

    @Resource
    private AwsShareGroupMapper shareGroupMapper;

    @Resource
    private AwsFailoverConfigMapper failoverMapper;

    @Resource
    private AwsAccountServiceImpl accountService;

    @Resource
    private AwsEc2ProviderService providerService;

    @Resource private AwsWorkflowService workflow;
    @Resource private com.admin.mapper.NodeMapper nodeMapper;

    @Override
    public List<Map<String, Object>> listInstances(AwsEc2ListDto dto) {
        List<Map<String, Object>> result = syncInstances(dto);
        if (dto == null) return result;
        String keyword = trim(dto.getKeyword()).toLowerCase();
        String state = trim(dto.getState()).toLowerCase();
        if (!StringUtils.hasText(keyword) && !StringUtils.hasText(state)) return result;
        result.removeIf(item -> {
            String itemState = String.valueOf(item.getOrDefault("state", "")).toLowerCase();
            String itemId = String.valueOf(item.getOrDefault("instanceId", "")).toLowerCase();
            String itemName = String.valueOf(item.getOrDefault("name", "")).toLowerCase();
            return StringUtils.hasText(state) && !state.equals(itemState)
                    || StringUtils.hasText(keyword) && !itemId.contains(keyword) && !itemName.contains(keyword);
        });
        return result;
    }

    @Override
    public List<Map<String, Object>> syncInstances(AwsEc2ListDto dto) {
        String requestedRegion = dto == null ? null : dto.getRegion();
        String instanceId = dto == null ? null : dto.getInstanceId();
        Long accountId = dto == null ? null : dto.getAccountId();
        List<AwsAccount> accounts = accountId == null
                ? accountMapper.selectList(new QueryWrapper<AwsAccount>().and(q -> q.eq("status", 0).or().isNull("status")).orderByAsc("id"))
                : List.of(accountService.requireAccount(accountId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AwsAccount account : accounts) {
            accountService.prepareAccount(account);
            List<String> regions = StringUtils.hasText(requestedRegion) ? List.of(requestedRegion.trim()) : configuredRegions(account);
            for (String region : regions) {
                result.addAll(providerService.listInstances(account, accountService.decryptAccessSecret(account),
                        accountService.decryptSessionToken(account), region, instanceId));
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> action(AwsEc2ActionDto dto) {
        if (dto == null) throw new IllegalArgumentException("AWS EC2 操作数据无效");
        AwsAccount account = accountService.requireAccount(dto.getAccountId());
        accountService.prepareAccount(account);
        String region = resolveRegion(account, dto.getRegion());
        return providerService.action(account, accountService.decryptAccessSecret(account),
                accountService.decryptSessionToken(account), region, dto);
    }

    @Override
    public Map<String, Object> changeIp(AwsEc2ChangeIpDto dto) {
        if (dto == null) throw new IllegalArgumentException("AWS 换 IP 数据无效");
        AwsAccount account = accountService.requireAccount(dto.getAccountId());
        accountService.prepareAccount(account);
        String region = resolveRegion(account, dto.getRegion());
        return providerService.changeIp(account, accountService.decryptAccessSecret(account),
                accountService.decryptSessionToken(account), region, dto);
    }

    public Map<String, Object> changeIpForNode(Long accountId, String instanceId, String region) {
        AwsEc2ChangeIpDto dto = new AwsEc2ChangeIpDto();
        dto.setAccountId(accountId);
        dto.setInstanceId(instanceId);
        dto.setRegion(region);
        dto.setStrategy("AUTO");
        dto.setReleaseOld(true);
        return changeIp(dto);
    }

    @Override
    public Map<String, Object> gfwCheck(AwsEc2ListDto dto) {
        if (dto == null) throw new IllegalArgumentException("AWS GFW 检查数据无效");
        AwsAccount account = accountService.requireAccount(dto.getAccountId());
        accountService.prepareAccount(account);
        String region = resolveRegion(account, dto.getRegion());
        return providerService.gfwCheck(account, accountService.decryptAccessSecret(account),
                accountService.decryptSessionToken(account), region, dto.getInstanceId());
    }

    @Override
    public Map<String, Object> createInstance(AwsEc2CreateDto dto) {
        if (dto == null) throw new IllegalArgumentException("AWS 创建参数无效");
        AwsAccount account = accountService.requireAccount(dto.getAccountId());
        AwsEc2InstanceTemplate template = dto.getTemplateId() == null ? null : templateMapper.selectById(dto.getTemplateId());
        if (dto.getTemplateId() != null && template == null) throw new IllegalArgumentException("AWS 模板不存在");
        accountService.prepareAccount(account);
        String requestedRegion = dto.getRegion() != null ? dto.getRegion() : template == null ? null : template.getRegion();
        String region = resolveRegion(account, requestedRegion);
        if (template != null) {
            dto.setImageId(textOr(dto.getImageId(), template.getImageId()));
            dto.setInstanceType(textOr(dto.getInstanceType(), template.getInstanceType()));
            dto.setKeyName(textOr(dto.getKeyName(), template.getKeyName()));
            dto.setSubnetId(textOr(dto.getSubnetId(), template.getSubnetId()));
            dto.setAvailabilityZone(textOr(dto.getAvailabilityZone(), template.getAvailabilityZone()));
            dto.setArchitecture(textOr(dto.getArchitecture(), template.getArchitecture()));
            if (dto.getUserData() == null) dto.setUserData(template.getUserData());
            if (dto.getSecurityGroupIds() == null) dto.setSecurityGroupIds(splitCsv(template.getSecurityGroupIds()));
            if (dto.getRootVolumeSize() == null && dto.getDiskGiB() == null) {
                dto.setRootVolumeSize(StringUtils.hasText(template.getRootVolumeSize()) ? Integer.valueOf(template.getRootVolumeSize()) : template.getDiskGiB());
            }
            if (dto.getSpot() == null) dto.setSpot(template.getSpot());
            if (dto.getEnableIpv6() == null) dto.setEnableIpv6(template.getEnableIpv6());
            if (dto.getDisablePublicIp() == null) dto.setDisablePublicIp(template.getDisablePublicIp());
            if (dto.getStaticIp() == null) dto.setStaticIp(template.getStaticIp());
            dto.setCidr(textOr(dto.getCidr(), template.getCidr()));
            if (!StringUtils.hasText(dto.getPassword()) && StringUtils.hasText(template.getPasswordEncrypted()))
                dto.setPassword(accountService.decryptValue(template.getPasswordEncrypted()));
        }
        return providerService.createInstance(account, accountService.decryptAccessSecret(account),
                accountService.decryptSessionToken(account), region, dto);
    }

    @Override
    @Transactional
    public R saveProfile(AwsEc2ProfileSaveDto dto) {
        if (dto == null || dto.getAccountId() == null || !StringUtils.hasText(dto.getInstanceId())) {
            return R.err("AWS EC2 实例档案数据无效");
        }
        if (!accountService.accountExists(dto.getAccountId())) return R.err("AWS 账号不存在");
        AwsEc2InstanceProfile profile = dto.getId() == null ? new AwsEc2InstanceProfile() : profileMapper.selectById(dto.getId());
        if (dto.getId() != null && profile == null) return R.err("AWS EC2 实例档案不存在");
        if (profile == null) profile = new AwsEc2InstanceProfile();
        profile.setAccountId(dto.getAccountId());
        profile.setRegion(trim(dto.getRegion()));
        profile.setInstanceId(trim(dto.getInstanceId()));
        profile.setName(trim(dto.getName()));
        profile.setInstanceType(trim(dto.getInstanceType()));
        profile.setImageId(trim(dto.getImageId()));
        profile.setSubnetId(trim(dto.getSubnetId()));
        profile.setSecurityGroupIds(trim(dto.getSecurityGroupIds()));
        profile.setRootVolumeSize(trim(dto.getRootVolumeSize()));
        profile.setUserData(dto.getUserData());
        profile.setTagsJson(dto.getTagsJson());
        profile.setRemark(trim(dto.getRemark()));
        saveAudit(profile);
        if (dto.getId() == null) profileMapper.insert(profile); else profileMapper.updateById(profile);
        return R.ok();
    }

    @Override
    public List<Map<String, Object>> listProfiles(Long accountId, String region) {
        QueryWrapper<AwsEc2InstanceProfile> query = new QueryWrapper<>();
        if (accountId != null) query.eq("account_id", accountId);
        if (StringUtils.hasText(region)) query.eq("region", region.trim());
        List<Map<String, Object>> result = new ArrayList<>();
        for (AwsEc2InstanceProfile profile : profileMapper.selectList(query.orderByDesc("id"))) {
            result.add(profileMap(profile));
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> listTemplates(Long accountId, String region) {
        QueryWrapper<AwsEc2InstanceTemplate> query = new QueryWrapper<>();
        if (accountId != null) query.eq("account_id", accountId);
        if (StringUtils.hasText(region)) query.eq("region", region.trim());
        List<Map<String, Object>> result = new ArrayList<>();
        for (AwsEc2InstanceTemplate template : templateMapper.selectList(query.orderByDesc("id"))) {
            result.add(templateMap(template));
        }
        return result;
    }

    @Override
    @Transactional
    public R saveTemplate(AwsEc2TemplateSaveDto dto) {
        if (dto == null || !StringUtils.hasText(dto.getName())) return R.err("AWS 模板名称不能为空");
        if (dto.getAccountId() != null && !accountService.accountExists(dto.getAccountId())) {
            return R.err("AWS 账号不存在");
        }
        AwsEc2InstanceTemplate template = dto.getId() == null ? new AwsEc2InstanceTemplate() : templateMapper.selectById(dto.getId());
        if (dto.getId() != null && template == null) return R.err("AWS 模板不存在");
        if (template == null) template = new AwsEc2InstanceTemplate();
        template.setAccountId(dto.getAccountId());
        template.setRegion(trim(dto.getRegion()));
        template.setAvailabilityZone(trim(dto.getAvailabilityZone()));
        template.setName(trim(dto.getName()));
        template.setImageId(trim(dto.getImageId()));
        template.setInstanceType(trim(dto.getInstanceType()));
        template.setKeyName(trim(dto.getKeyName()));
        template.setSubnetId(trim(dto.getSubnetId()));
        template.setSecurityGroupIds(trim(dto.getSecurityGroupIds()));
        template.setRootVolumeSize(trim(dto.getRootVolumeSize()));
        template.setArchitecture(trim(dto.getArchitecture()));
        template.setDiskGiB(dto.getDiskGiB());
        template.setSpot(dto.getSpot());
        template.setEnableIpv6(dto.getEnableIpv6());
        template.setDisablePublicIp(dto.getDisablePublicIp());
        template.setStaticIp(dto.getStaticIp());
        template.setCidr(trim(dto.getCidr()));
        if (StringUtils.hasText(dto.getPassword())) template.setPasswordEncrypted(accountService.encryptValue(dto.getPassword()));
        template.setUserData(dto.getUserData());
        template.setTagsJson(dto.getTagsJson());
        template.setRemark(trim(dto.getRemark()));
        saveAudit(template);
        if (dto.getId() == null) templateMapper.insert(template); else templateMapper.updateById(template);
        return R.ok();
    }

    @Override
    @Transactional
    public R deleteTemplate(AwsEc2TemplateIdDto dto) {
        if (dto == null || dto.getId() == null || templateMapper.selectById(dto.getId()) == null) {
            return R.err("AWS 模板不存在");
        }
        if (failoverMapper.selectCount(new QueryWrapper<AwsFailoverConfig>().eq("template_id", dto.getId())) > 0)
            return R.err("自动替补配置正在引用此模板，请先更换模板");
        templateMapper.deleteById(dto.getId());
        return R.ok();
    }

    @Override
    public List<Map<String, Object>> listShareGroups() {
        return workflow.listShares();
    }

    @Override
    @Transactional
    public R saveShareGroup(AwsShareGroupSaveDto dto) {
        return workflow.saveShare(dto);
    }

    @Override
    @Transactional
    public R deleteShareGroup(AwsShareGroupIdDto dto) {
        if (dto == null || dto.getId() == null) return R.err("分享组不存在");
        return shareGroupMapper.deleteById(dto.getId()) == 1 ? R.ok() : R.err("分享组不存在");
    }

    @Override
    public Map<String, Object> getFailoverConfig() {
        return workflow.configView();
    }

    @Override
    @Transactional
    public R saveFailoverConfig(AwsFailoverConfigDto dto) {
        return workflow.saveConfig(dto);
    }

    @Override
    @Transactional
    public AwsNodeBinding bindNode(Long nodeId, Long accountId, String instanceId, String region) {
        if (nodeId == null || accountId == null || !StringUtils.hasText(instanceId)) {
            throw new IllegalArgumentException("AWS 节点绑定参数无效");
        }
        if (!accountService.accountExists(accountId)) throw new IllegalArgumentException("AWS 账号不存在");
        AwsNodeBinding binding = bindingMapper.selectById(nodeId);
        if (binding == null) {
            binding = new AwsNodeBinding();
            binding.setNodeId(nodeId);
            binding.setUpdatedTime(System.currentTimeMillis());
            binding.setAccountId(accountId);
            binding.setInstanceId(instanceId.trim());
            binding.setRegion(trim(region));
            bindingMapper.insert(binding);
        } else {
            binding.setAccountId(accountId);
            binding.setInstanceId(instanceId.trim());
            binding.setRegion(trim(region));
            binding.setUpdatedTime(System.currentTimeMillis());
            bindingMapper.updateById(binding);
        }
        return binding;
    }

    @Override
    public AwsNodeBinding resolveNodeBinding(Long nodeId) {
        return nodeId == null ? null : bindingMapper.selectById(nodeId);
    }

    private String resolveRegion(AwsAccount account, String requested) {
        String region = StringUtils.hasText(requested) ? requested.trim() : account.getRegion();
        if (!StringUtils.hasText(region)) throw new IllegalArgumentException("AWS 区域不能为空");
        return region;
    }

    private List<String> configuredRegions(AwsAccount account) {
        String value = StringUtils.hasText(account.getRegions()) ? account.getRegions() : account.getRegion();
        if (!StringUtils.hasText(value)) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasText).distinct().toList();
    }

    private String textOr(String value, String fallback) { return StringUtils.hasText(value) ? value.trim() : fallback; }

    private List<String> splitCsv(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }

    private void saveAudit(com.admin.entity.BaseEntity entity) {
        long now = System.currentTimeMillis();
        if (entity.getId() == null) {
            entity.setCreatedTime(now);
            entity.setStatus(0);
        } else {
            entity.setUpdatedTime(now);
        }
    }

    private Map<String, Object> profileMap(AwsEc2InstanceProfile profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", profile.getId());
        result.put("accountId", profile.getAccountId());
        result.put("region", profile.getRegion());
        result.put("instanceId", profile.getInstanceId());
        result.put("name", profile.getName());
        result.put("instanceType", profile.getInstanceType());
        result.put("imageId", profile.getImageId());
        result.put("subnetId", profile.getSubnetId());
        result.put("securityGroupIds", profile.getSecurityGroupIds());
        result.put("rootVolumeSize", profile.getRootVolumeSize());
        result.put("userData", profile.getUserData());
        result.put("tagsJson", profile.getTagsJson());
        result.put("remark", profile.getRemark());
        return result;
    }

    private Map<String, Object> templateMap(AwsEc2InstanceTemplate template) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", template.getId());
        result.put("accountId", template.getAccountId());
        result.put("region", template.getRegion());
        result.put("availabilityZone", template.getAvailabilityZone());
        result.put("passwordConfigured", StringUtils.hasText(template.getPasswordEncrypted()));
        result.put("name", template.getName());
        result.put("imageId", template.getImageId());
        result.put("instanceType", template.getInstanceType());
        result.put("keyName", template.getKeyName());
        result.put("subnetId", template.getSubnetId());
        result.put("securityGroupIds", template.getSecurityGroupIds());
        result.put("rootVolumeSize", template.getRootVolumeSize());
        result.put("architecture", template.getArchitecture()); result.put("diskGiB", template.getDiskGiB());
        result.put("spot", template.getSpot()); result.put("enableIpv6", template.getEnableIpv6());
        result.put("disablePublicIp", template.getDisablePublicIp()); result.put("staticIp", template.getStaticIp());
        result.put("cidr", template.getCidr());
        result.put("userData", template.getUserData());
        result.put("tagsJson", template.getTagsJson());
        result.put("remark", template.getRemark());
        return result;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

}
