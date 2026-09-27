package com.admin.service.impl;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.config.EncryptionConfig;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.admin.service.AwsAccountService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaRequest;
import software.amazon.awssdk.services.sts.StsClient;

import javax.annotation.Resource;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** AWS credentials never leave the administrator-only editor and never appear in list responses. */
@Slf4j
@Service
public class AwsAccountServiceImpl implements AwsAccountService {
    private static final Pattern ACCESS_KEY = Pattern.compile("^[A-Za-z0-9]{16,128}$");
    private static final Pattern REGION = Pattern.compile("^[a-z]{2,3}(-gov)?-[a-z0-9-]+-[0-9]+$");
    @Resource private AwsAccountMapper accountMapper;
    @Resource private AwsEc2InstanceProfileMapper profileMapper;
    @Resource private AwsEc2InstanceTemplateMapper templateMapper;
    @Resource private AwsNodeBindingMapper bindingMapper;
    @Resource private NodeMapper nodeMapper;
    @Resource private AwsAccountGroupMapper groupMapper;
    @Resource private AwsProxyPoolMapper proxyMapper;
    @Resource private AwsFailoverConfigMapper failoverMapper;
    @Resource private AwsOperationMapper operationMapper;
    @Resource private AwsFailoverJobMapper failoverJobMapper;
    @Resource private AwsClientFactory clientFactory;
    @Value("${AWS_ENCRYPTION_KEY:}") private String encryptionSecret;

    @Override
    public List<Map<String, Object>> listAccounts() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AwsAccount account : accountMapper.selectList(new QueryWrapper<AwsAccount>().orderByAsc("id"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", account.getId()); item.put("name", account.getName());
            item.put("accessKeyId", maskedKey(account.getAccessKeyId()));
            item.put("groupId", account.getGroupId()); item.put("groupName", account.getGroupName());
            item.put("proxyId", account.getProxyPoolId()); item.put("region", account.getRegion());
            item.put("regions", StringUtils.hasText(account.getRegions()) ? account.getRegions() : account.getRegion());
            item.put("proxyConfigured", account.getProxyPoolId() != null || StringUtils.hasText(account.getProxy()) || StringUtils.hasText(account.getProxyEncrypted()));
            item.put("sessionTokenConfigured", StringUtils.hasText(account.getSessionTokenEncrypted()));
            item.put("mfaConfigured", StringUtils.hasText(account.getMfaSerialNumber()) && StringUtils.hasText(account.getMfaSecretEncrypted()));
            item.put("mfaSerialNumber", account.getMfaSerialNumber()); item.put("roleArn", account.getRoleArn()); item.put("roleSessionName", account.getRoleSessionName());
            item.put("enabled", account.getStatus() == null || account.getStatus() == 0);
            item.put("remark", account.getRemark()); result.add(item);
        }
        return result;
    }

    @Override @Transactional
    public R saveAccount(AwsAccountSaveDto dto) {
        try {
            if (dto == null) return R.err("AWS 账号数据无效");
            AwsAccount account = dto.getId() == null ? new AwsAccount() : accountMapper.selectById(dto.getId());
            if (account == null) return R.err("AWS 账号不存在");
            String name = trim(dto.getName()); List<String> regions = dto.resolvedRegions();
            if (name.isEmpty() || name.length() > 120 || regions.isEmpty() || regions.size() > 50 || regions.stream().anyMatch(item -> !REGION.matcher(item).matches()))
                return R.err("请检查 AWS 账号名称和区域格式");
            String accessKey = trim(dto.getAccessKeyId());
            boolean preserveKey = dto.getId() != null && (accessKey.isEmpty() || accessKey.equals(maskedKey(account.getAccessKeyId())));
            if (!preserveKey && !ACCESS_KEY.matcher(accessKey).matches()) return R.err("Access Key ID 格式无效");
            if (preserveKey && !StringUtils.hasText(account.getAccessKeyEncrypted()) && !ACCESS_KEY.matcher(trim(account.getAccessKeyId())).matches()) return R.err("请重新填写完整 Access Key ID");
            String secret = trim(dto.resolvedAccessSecret());
            if (dto.getId() == null && secret.isEmpty()) return R.err("新增 AWS 账号时必须填写 Secret Access Key");
            if (!secret.isEmpty() && (secret.length() < 8 || secret.length() > 4096)) return R.err("Secret Access Key 格式无效");
            boolean clearMfa = Boolean.TRUE.equals(dto.getClearMfa());
            String mfaSerial = clearMfa ? "" : StringUtils.hasText(dto.resolvedMfaSerial()) ? trim(dto.resolvedMfaSerial()) : trim(account.getMfaSerialNumber());
            boolean mfaSecret = !clearMfa && (StringUtils.hasText(dto.getMfaSecret()) || StringUtils.hasText(account.getMfaSecretEncrypted()));
            if (StringUtils.hasText(mfaSerial) != mfaSecret) return R.err("配置 MFA 时必须同时填写 MFA Serial 和 MFA Secret");
            if (!clearMfa && StringUtils.hasText(dto.getMfaSecret())) generateTotp(dto.getMfaSecret(), Instant.now());
            boolean hasToken = !Boolean.TRUE.equals(dto.getClearSessionToken()) && (StringUtils.hasText(dto.getSessionToken()) || StringUtils.hasText(account.getSessionTokenEncrypted()));
            if (hasToken && mfaSecret) return R.err("Session Token 临时凭据不能再次申请 MFA 会话，请二选一");
            if (dto.getProxyId() != null && proxyMapper.selectById(dto.getProxyId()) == null) return R.err("代理池记录不存在");
            if (StringUtils.hasText(dto.getProxy()) && dto.getProxyId() != null) return R.err("直接代理和代理池只能选一个");
            if (StringUtils.hasText(dto.getProxy())) AwsClientFactory.validateProxy(dto.getProxy());
            AESCrypto crypto = requiredCrypto();
            AwsAccountGroup group = resolveGroup(dto.getGroupId(), dto.resolvedGroup());
            if (!preserveKey) { account.setAccessKeyEncrypted(crypto.encrypt(accessKey)); account.setAccessKeyId(maskedKey(accessKey)); }
            else if (!StringUtils.hasText(account.getAccessKeyEncrypted())) { account.setAccessKeyEncrypted(crypto.encrypt(account.getAccessKeyId())); account.setAccessKeyId(maskedKey(account.getAccessKeyId())); }
            account.setName(name); account.setRegion(regions.get(0)); account.setRegions(String.join(",", regions));
            account.setRemark(limited(dto.resolvedRemark(), 1000, "备注")); account.setGroupId(group == null ? null : group.getId()); account.setGroupName(group == null ? "" : group.getName());
            if (dto.getEnabled() != null) account.setStatus(dto.getEnabled() ? 0 : 1);
            if (dto.getProxyId() != null) { account.setProxyPoolId(dto.getProxyId()); account.setProxy(""); account.setProxyEncrypted(""); }
            else if (StringUtils.hasText(dto.getProxy())) {
                URI proxy = AwsClientFactory.validateProxy(dto.getProxy()); account.setProxyPoolId(null); account.setProxy(proxyEndpoint(proxy)); account.setProxyEncrypted(crypto.encrypt(dto.getProxy().trim()));
            } else if (Boolean.TRUE.equals(dto.getClearProxy())) { account.setProxyPoolId(null); account.setProxy(""); account.setProxyEncrypted(""); }
            if (!secret.isEmpty()) account.setAccessSecretEncrypted(crypto.encrypt(secret));
            if (Boolean.TRUE.equals(dto.getClearSessionToken())) account.setSessionTokenEncrypted("");
            else if (StringUtils.hasText(dto.getSessionToken())) account.setSessionTokenEncrypted(crypto.encrypt(limited(dto.getSessionToken(), 16384, "Session Token")));
            account.setMfaSerialNumber(mfaSerial);
            if (clearMfa) account.setMfaSecretEncrypted(""); else if (StringUtils.hasText(dto.getMfaSecret())) account.setMfaSecretEncrypted(crypto.encrypt(dto.getMfaSecret().trim()));
            if (Boolean.TRUE.equals(dto.getClearRole())) { account.setRoleArn(""); account.setRoleSessionName(""); account.setRoleExternalIdEncrypted(""); }
            else {
                if (StringUtils.hasText(dto.getRoleArn())) account.setRoleArn(limited(dto.getRoleArn(), 2048, "Role ARN"));
                if (StringUtils.hasText(dto.getRoleSessionName())) account.setRoleSessionName(limited(dto.getRoleSessionName(), 64, "Role Session Name"));
                if (StringUtils.hasText(dto.getRoleExternalId())) account.setRoleExternalIdEncrypted(crypto.encrypt(limited(dto.getRoleExternalId(), 1224, "Role External ID")));
            }
            long now = System.currentTimeMillis();
            if (dto.getId() == null) { account.setCreatedTime(now); if (account.getStatus() == null) account.setStatus(0); accountMapper.insert(account); }
            else {
                account.setUpdatedTime(now); accountMapper.updateById(account);
                accountMapper.update(null, new UpdateWrapper<AwsAccount>().eq("id", account.getId()).set("group_id", account.getGroupId()).set("proxy_pool_id", account.getProxyPoolId()));
            }
            clientFactory.invalidate(account.getId()); return R.ok();
        } catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }

    @Override @Transactional public R deleteAccount(Long id) {
        if (id == null || accountMapper.selectById(id) == null) return R.err("AWS 账号不存在");
        if (profileMapper.selectCount(new QueryWrapper<AwsEc2InstanceProfile>().eq("account_id", id)) > 0
                || templateMapper.selectCount(new QueryWrapper<AwsEc2InstanceTemplate>().eq("account_id", id)) > 0
                || bindingMapper.selectCount(new QueryWrapper<AwsNodeBinding>().eq("account_id", id)) > 0
                || nodeMapper.selectCount(new QueryWrapper<Node>().eq("aws_account_id", id)) > 0
                || operationMapper.selectCount(new QueryWrapper<AwsOperation>().eq("account_id", id).isNotNull("active_target")) > 0
                || failoverJobMapper.selectCount(new QueryWrapper<AwsFailoverJob>()
                .and(q -> q.eq("old_account_id", id).or().eq("new_account_id", id)).in("status", "PENDING", "QUEUED", "RUNNING", "CREATING", "WAITING")) > 0)
            return R.err("仍有 AWS 实例档案、模板或节点绑定此账号，请先解除绑定");
        accountMapper.deleteById(id); clientFactory.invalidate(id); return R.ok();
    }

    @Override public Map<String, String> getPrivateSecrets(Long id) {
        AwsAccount account = requireAccount(id); Map<String, String> result = new LinkedHashMap<>();
        result.put("accessKeyId", decryptAccessKey(account)); result.put("accessSecret", decryptSecret(account)); result.put("secretAccessKey", result.get("accessSecret"));
        result.put("sessionToken", decryptOptional(account.getSessionTokenEncrypted())); result.put("mfaSecret", decryptOptional(account.getMfaSecretEncrypted())); result.put("mfaSerial", trim(account.getMfaSerialNumber()));
        result.put("proxy", account.getProxyPoolId() == null ? decryptProxy(account) : ""); result.put("proxyUrl", result.get("proxy")); result.put("roleExternalId", decryptOptional(account.getRoleExternalIdEncrypted())); return result;
    }
    @Override public Map<String, Object> testAccount(Long id, String region) {
        AwsAccount account = requireAccount(id); prepareAccount(account);
        try (StsClient sts = clientFactory.sts(account, decryptSecret(account), decryptSessionToken(account), region)) {
            Map<String, Object> result = new LinkedHashMap<>(); result.put("ok", true); result.put("awsAccountId", sts.getCallerIdentity().account()); result.put("region", selectedRegion(account, region)); return result;
        } catch (Exception e) {
            log.warn("AWS account test failed, accountId={}, type={}", id, e.getClass().getSimpleName()); return Map.of("ok", false, "message", "AWS 连接失败，请检查凭据、MFA、代理和访问策略");
        }
    }
    @Override public Map<String, Object> quota(Long accountId, String region) {
        AwsAccount account = requireAccount(accountId); prepareAccount(account); String selectedRegion = selectedRegion(account, region);
        try (ServiceQuotasClient quotas = clientFactory.serviceQuotas(account, decryptSecret(account), decryptSessionToken(account), selectedRegion);
             Ec2Client ec2 = clientFactory.ec2(account, decryptSecret(account), decryptSessionToken(account), selectedRegion)) {
            Double limit = quotas.getServiceQuota(GetServiceQuotaRequest.builder().serviceCode("ec2").quotaCode("L-1216C47A").build()).quota().value();
            if (limit == null || !Double.isFinite(limit)) throw new IllegalStateException("AWS 配额数据不可用");
            List<Instance> instances = new ArrayList<>();
            for (DescribeInstancesResponse page : ec2.describeInstancesPaginator(DescribeInstancesRequest.builder().filters(Filter.builder().name("instance-state-name").values("pending", "running").build()).maxResults(100).build())) page.reservations().forEach(item -> instances.addAll(item.instances()));
            Map<String, Integer> cpus = new HashMap<>();
            List<String> types = instances.stream().filter(item -> item.instanceLifecycle() != InstanceLifecycleType.SPOT).map(Instance::instanceTypeAsString).filter(AwsAccountServiceImpl::standardFamily).distinct().toList();
            for (int offset = 0; offset < types.size(); offset += 100) {
                for (InstanceTypeInfo info : ec2.describeInstanceTypes(DescribeInstanceTypesRequest.builder().instanceTypesWithStrings(types.subList(offset, Math.min(types.size(), offset + 100))).build()).instanceTypes()) if (info.vCpuInfo() != null) cpus.put(info.instanceTypeAsString(), info.vCpuInfo().defaultVCpus());
            }
            int used = 0;
            for (Instance instance : instances) {
                if (instance.instanceLifecycle() == InstanceLifecycleType.SPOT || !standardFamily(instance.instanceTypeAsString())) continue;
                Integer count = cpus.get(instance.instanceTypeAsString()); if (count == null) throw new IllegalStateException("AWS 未返回完整实例 vCPU 数据"); used += count;
            }
            Map<String, Object> result = new LinkedHashMap<>(); result.put("quota", limit); result.put("used", used); result.put("available", Math.max(0d, limit - used)); result.put("vcpuQuota", limit); result.put("vcpuUsed", used); result.put("region", selectedRegion); result.put("quotaScope", "STANDARD_ON_DEMAND");
            try { result.put("spotVcpuQuota", quotas.getServiceQuota(GetServiceQuotaRequest.builder().serviceCode("ec2").quotaCode("L-34B43A08").build()).quota().value()); } catch (RuntimeException ignored) { result.put("spotVcpuQuota", null); }
            return result;
        } catch (Exception e) {
            log.warn("AWS quota lookup failed, accountId={}, type={}", accountId, e.getClass().getSimpleName()); throw new IllegalStateException("无法读取 AWS vCPU 配额；请检查账号、区域及 Service Quotas/EC2 读取权限");
        }
    }
    private static boolean standardFamily(String type) { return type != null && type.matches("^[acdhimrtz][0-9].*"); }
    private String selectedRegion(AwsAccount account, String region) { return StringUtils.hasText(region) ? region.trim() : account.getRegion(); }
    @Override public boolean accountExists(Long id) { return id != null && accountMapper.selectById(id) != null; }
    public AwsAccount requireAccount(Long id) { if (id == null) throw new IllegalArgumentException("AWS 账号 ID 无效"); AwsAccount account = accountMapper.selectById(id); if (account == null) throw new IllegalArgumentException("AWS 账号不存在"); return account; }
    public String decryptAccessKey(AwsAccount account) {
        if (StringUtils.hasText(account.getAccessKeyEncrypted())) return decrypt(account.getAccessKeyEncrypted(), "Access Key");
        if (!ACCESS_KEY.matcher(trim(account.getAccessKeyId())).matches()) throw new IllegalStateException("AWS Access Key 未配置");
        String raw = account.getAccessKeyId();
        if (account.getId() != null) { String encrypted = encryptValue(raw); accountMapper.update(null, new UpdateWrapper<AwsAccount>().eq("id", account.getId()).isNull("access_key_encrypted").set("access_key_encrypted", encrypted).set("access_key_id", maskedKey(raw))); account.setAccessKeyEncrypted(encrypted); account.setAccessKeyId(maskedKey(raw)); }
        return raw;
    }
    public String decryptSecret(AwsAccount account) { return decrypt(account.getAccessSecretEncrypted(), "Access Secret"); }
    public String decryptAccessSecret(AwsAccount account) { return decryptSecret(account); }
    public String decryptSessionToken(AwsAccount account) { return decryptOptional(account.getSessionTokenEncrypted()); }
    public String decryptMfaSecret(AwsAccount account) { return decryptOptional(account.getMfaSecretEncrypted()); }
    public String decryptProxy(AwsAccount account) {
        if (account.getProxyPoolId() != null) { AwsProxyPool pool = proxyMapper.selectById(account.getProxyPoolId()); if (pool == null) throw new IllegalStateException("AWS 代理池记录不存在"); return decrypt(pool.getProxyEncrypted(), "代理凭据"); }
        return StringUtils.hasText(account.getProxyEncrypted()) ? decrypt(account.getProxyEncrypted(), "代理凭据") : trim(account.getProxy());
    }
    public void prepareAccount(AwsAccount account) {
        if (account.getStatus() != null && account.getStatus() != 0) throw new IllegalStateException("AWS 账号已停用");
        account.setAccessKeyResolved(decryptAccessKey(account)); account.setProxyResolved(decryptProxy(account));
        if (StringUtils.hasText(account.getMfaSerialNumber())) account.setMfaTokenCode(generateTotp(decryptMfaSecret(account), Instant.now()));
        account.setRoleExternalId(decryptOptional(account.getRoleExternalIdEncrypted()));
    }
    public static String generateTotp(String secret, Instant instant) {
        try {
            String normalized = trim(secret).replace("=", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (normalized.length() < 16 || normalized.length() > 256) throw new IllegalArgumentException();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0;
            for (char c : normalized.toCharArray()) { int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c); if (digit < 0) throw new IllegalArgumentException(); buffer = (buffer << 5) | digit; bits += 5; if (bits >= 8) { bits -= 8; output.write((buffer >> bits) & 255); } }
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1"); mac.init(new javax.crypto.spec.SecretKeySpec(output.toByteArray(), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(instant.getEpochSecond() / 30).array()); int offset = hash[hash.length - 1] & 15;
            int value = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16) | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (Exception e) { throw new IllegalArgumentException("AWS MFA Secret 必须是有效的 Base32 密钥"); }
    }

    @Override public List<Map<String, Object>> listAccountGroups() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AwsAccountGroup group : groupMapper.selectList(new QueryWrapper<AwsAccountGroup>().orderByAsc("id"))) {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("id", group.getId()); item.put("name", group.getName()); item.put("remark", group.getRemark()); item.put("accountCount", accountMapper.selectCount(new QueryWrapper<AwsAccount>().eq("group_id", group.getId()))); result.add(item);
        }
        return result;
    }
    @Override @Transactional public R saveAccountGroup(AwsAccountGroupSaveDto dto) {
        if (dto == null || !StringUtils.hasText(dto.getName()) || dto.getName().trim().length() > 120) return R.err("请填写有效的分组名称");
        QueryWrapper<AwsAccountGroup> duplicate = new QueryWrapper<AwsAccountGroup>().eq("name", dto.getName().trim()); if (dto.getId() != null) duplicate.ne("id", dto.getId()); if (groupMapper.selectCount(duplicate) > 0) return R.err("分组名称已存在");
        AwsAccountGroup group = dto.getId() == null ? new AwsAccountGroup() : groupMapper.selectById(dto.getId()); if (group == null) return R.err("账号分组不存在");
        group.setName(dto.getName().trim()); group.setRemark(limited(dto.getRemark(), 500, "备注"));
        if (dto.getId() == null) { group.setCreatedTime(System.currentTimeMillis()); group.setStatus(0); groupMapper.insert(group); }
        else { group.setUpdatedTime(System.currentTimeMillis()); groupMapper.updateById(group); accountMapper.update(null, new UpdateWrapper<AwsAccount>().eq("group_id", group.getId()).set("group_name", group.getName())); }
        return R.ok();
    }
    @Override @Transactional public R deleteAccountGroup(Long id) {
        if (id == null || groupMapper.selectById(id) == null) return R.err("账号分组不存在");
        if (accountMapper.selectCount(new QueryWrapper<AwsAccount>().eq("group_id", id)) > 0 || failoverMapper.selectCount(new QueryWrapper<AwsFailoverConfig>().eq("backup_group_id", id)) > 0) return R.err("账号或自动替补仍在使用此分组");
        groupMapper.deleteById(id); return R.ok();
    }
    private AwsAccountGroup resolveGroup(Long groupId, String name) {
        if (groupId != null) { AwsAccountGroup group = groupMapper.selectById(groupId); if (group == null) throw new IllegalArgumentException("AWS 账号分组不存在"); return group; }
        if (!StringUtils.hasText(name)) return null; String normalized = limited(name, 120, "分组名称");
        AwsAccountGroup group = groupMapper.selectOne(new QueryWrapper<AwsAccountGroup>().eq("name", normalized));
        if (group == null) { group = new AwsAccountGroup(); group.setName(normalized); group.setStatus(0); group.setCreatedTime(System.currentTimeMillis()); groupMapper.insert(group); } return group;
    }
    @Override public List<Map<String, Object>> listProxyPools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AwsProxyPool proxy : proxyMapper.selectList(new QueryWrapper<AwsProxyPool>().orderByAsc("id"))) {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("id", proxy.getId()); item.put("name", proxy.getName()); item.put("endpoint", proxy.getEndpoint()); item.put("configured", StringUtils.hasText(proxy.getProxyEncrypted())); item.put("remark", proxy.getRemark()); result.add(item);
        }
        return result;
    }
    @Override @Transactional public R saveProxyPool(AwsProxySaveDto dto) {
        try {
            if (dto == null || !StringUtils.hasText(dto.getName()) || dto.getName().trim().length() > 120) return R.err("请填写有效的代理名称");
            QueryWrapper<AwsProxyPool> duplicate = new QueryWrapper<AwsProxyPool>().eq("name", dto.getName().trim()); if (dto.getId() != null) duplicate.ne("id", dto.getId()); if (proxyMapper.selectCount(duplicate) > 0) return R.err("代理名称已存在");
            AwsProxyPool pool = dto.getId() == null ? new AwsProxyPool() : proxyMapper.selectById(dto.getId()); if (pool == null) return R.err("代理池记录不存在");
            if (StringUtils.hasText(dto.getUrl())) { URI uri = AwsClientFactory.validateProxy(dto.getUrl()); pool.setEndpoint(proxyEndpoint(uri)); pool.setProxyEncrypted(encryptValue(dto.getUrl().trim())); }
            else if (dto.getId() == null) return R.err("新增代理需要填写代理地址");
            pool.setName(dto.getName().trim()); pool.setRemark(limited(dto.getRemark(), 500, "备注"));
            if (dto.getId() == null) { pool.setCreatedTime(System.currentTimeMillis()); pool.setStatus(0); proxyMapper.insert(pool); } else { pool.setUpdatedTime(System.currentTimeMillis()); proxyMapper.updateById(pool); }
            clientFactory.invalidateAll(); return R.ok();
        } catch (IllegalArgumentException | IllegalStateException e) { return R.err(e.getMessage()); }
    }
    @Override @Transactional public R deleteProxyPool(Long id) {
        if (id == null || proxyMapper.selectById(id) == null) return R.err("代理池记录不存在");
        if (accountMapper.selectCount(new QueryWrapper<AwsAccount>().eq("proxy_pool_id", id)) > 0) return R.err("仍有 AWS 账号使用此代理"); proxyMapper.deleteById(id); return R.ok();
    }
    @Override @Transactional public R importAccounts(AwsAccountImportDto dto) {
        if (dto == null || !StringUtils.hasText(dto.getText()) || dto.getText().length() > 250_000) return R.err("请填写批量导入内容，最多 250 KB");
        String region = StringUtils.hasText(dto.getRegion()) ? dto.getRegion().trim() : "us-east-1"; if (!REGION.matcher(region).matches()) return R.err("默认区域格式无效");
        String[] lines = dto.getText().split("\\R"); if (lines.length > 500) return R.err("每次最多导入 500 行");
        List<Map<String, Object>> failures = new ArrayList<>(); int imported = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            try {
                AwsAccountSaveDto account = parseImportLine(lines[i], region); account.setGroupId(dto.getGroupId()); R saved = saveAccount(account);
                if (saved.getCode() == 0) imported++; else failures.add(Map.of("line", i + 1, "message", saved.getMsg()));
            } catch (RuntimeException e) { failures.add(Map.of("line", i + 1, "message", "格式无效：AccessKey SecretKey [区域] [名称]")); }
        }
        return R.ok(Map.of("imported", imported, "failed", failures.size(), "errors", failures));
    }
    public static AwsAccountSaveDto parseImportLine(String line, String defaultRegion) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2 || parts.length > 4 || !ACCESS_KEY.matcher(parts[0]).matches() || parts[1].length() < 8) throw new IllegalArgumentException("AWS 导入格式无效");
        AwsAccountSaveDto dto = new AwsAccountSaveDto(); dto.setAccessKeyId(parts[0]); dto.setSecretAccessKey(parts[1]); dto.setRegion(defaultRegion); dto.setName("AWS-" + parts[0].substring(parts[0].length() - 4));
        if (parts.length >= 3) { if (REGION.matcher(parts[2]).matches()) dto.setRegion(parts[2]); else if (parts.length == 3) dto.setName(parts[2]); else throw new IllegalArgumentException("AWS 导入区域无效"); }
        if (parts.length == 4) dto.setName(parts[3]); return dto;
    }
    public String encryptStoredValue(String value) { return encryptValue(value); }
    @Override public String encryptValue(String value) { return requiredCrypto().encrypt(value); }
    @Override public String decryptValue(String value) { return decrypt(value, "保存的数据"); }
    private String decrypt(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalStateException("AWS " + label + " 未配置"); AESCrypto crypto = requiredCrypto();
        try { return crypto.decryptString(value); } catch (Exception e) { throw new IllegalStateException("AWS " + label + " 无法解密，请重新保存"); }
    }
    private String decryptOptional(String value) { return StringUtils.hasText(value) ? decrypt(value, "凭据") : ""; }
    private AESCrypto requiredCrypto() {
        if (!StringUtils.hasText(encryptionSecret)) throw new IllegalStateException("请先配置独立的 AWS_ENCRYPTION_KEY"); AESCrypto crypto = EncryptionConfig.getOrCreateCrypto(encryptionSecret); if (crypto == null) throw new IllegalStateException("AWS 加密密钥不可用"); return crypto;
    }
    private static String proxyEndpoint(URI uri) { try { return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString(); } catch (Exception e) { throw new IllegalArgumentException("代理地址格式无效"); } }
    private static String maskedKey(String value) { String key = trim(value); return key.length() < 4 ? "****" : "****" + key.substring(key.length() - 4); }
    private static String limited(String value, int limit, String label) { String text = trim(value); if (text.length() > limit) throw new IllegalArgumentException(label + "过长"); return text; }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
