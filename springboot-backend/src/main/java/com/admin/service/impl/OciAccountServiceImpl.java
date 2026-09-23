package com.admin.service.impl;

import com.admin.common.dto.OciAccountSaveDto;
import com.admin.common.dto.OciAccountTestResult;
import com.admin.common.dto.OciInstanceView;
import com.admin.common.lang.R;
import com.admin.common.utils.AESCrypto;
import com.admin.config.EncryptionConfig;
import com.admin.entity.OciAccount;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.OciAccountMapper;
import com.admin.service.OciAccountService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class OciAccountServiceImpl implements OciAccountService {
    private static final Pattern FINGERPRINT = Pattern.compile("^([0-9a-fA-F]{2}:){15}[0-9a-fA-F]{2}$");
    private static final Pattern REGION = Pattern.compile("^[a-z]+-[a-z]+-[0-9]+$");

    @Resource
    private OciAccountMapper accountMapper;

    @Resource
    private NodeMapper nodeMapper;

    @Resource
    private OciCloudService ociCloudService;

    @Value("${OCI_ENCRYPTION_KEY:${jwt-secret}}")
    private String encryptionSecret;

    @Override
    public List<Map<String, Object>> listAccounts() {
        List<OciAccount> accounts = accountMapper.selectList(new QueryWrapper<OciAccount>()
                .select("id", "name", "user_ocid", "tenancy_ocid", "fingerprint", "region")
                .orderByAsc("id"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OciAccount account : accounts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", account.getId());
            item.put("name", account.getName());
            item.put("userOcid", account.getUserOcid());
            item.put("tenancyOcid", account.getTenancyOcid());
            item.put("fingerprint", account.getFingerprint());
            item.put("region", account.getRegion());
            item.put("privateKeyConfigured", true);
            result.add(item);
        }
        return result;
    }

    @Override
    @Transactional
    public R saveAccount(OciAccountSaveDto dto) {
        if (dto == null) return R.err("OCI 账号数据无效");
        String name = trim(dto.getName());
        OciConfig config;
        try {
            config = parseConfig(dto.getConfigText());
        } catch (IllegalArgumentException e) {
            return R.err(e.getMessage());
        }
        String privateKey = dto.getPrivateKey() == null ? "" : dto.getPrivateKey().trim();
        if (!StringUtils.hasText(name) || name.length() > 120
                || !config.userOcid().startsWith("ocid1.user.")
                || !config.tenancyOcid().startsWith("ocid1.tenancy.")
                || !FINGERPRINT.matcher(config.fingerprint()).matches()
                || !REGION.matcher(config.region()).matches()) {
            return R.err("请检查账号名称以及 OCI 配置中的 User OCID、Tenancy OCID、指纹和区域格式");
        }

        OciAccount account = dto.getId() == null ? new OciAccount() : accountMapper.selectById(dto.getId());
        if (dto.getId() != null && account == null) return R.err("OCI 账号不存在");
        if (account == null) account = new OciAccount();
        if (dto.getId() == null && !StringUtils.hasText(privateKey)) {
            return R.err("新增 OCI 账号时必须填写 API 私钥");
        }
        if (StringUtils.hasText(privateKey)
                && (!privateKey.contains("BEGIN") || !privateKey.contains("PRIVATE KEY")
                || !privateKey.contains("END"))) {
            return R.err("API 私钥不是有效的 PEM 私钥格式");
        }

        account.setName(name);
        account.setUserOcid(config.userOcid());
        account.setTenancyOcid(config.tenancyOcid());
        account.setFingerprint(config.fingerprint());
        account.setRegion(config.region());
        if (StringUtils.hasText(privateKey)) {
            account.setPrivateKeyEncrypted(crypto().encrypt(privateKey.replace("\r\n", "\n")));
        }
        long now = System.currentTimeMillis();
        if (dto.getId() == null) {
            account.setCreatedTime(now);
            account.setStatus(0);
            accountMapper.insert(account);
        } else {
            account.setUpdatedTime(now);
            accountMapper.updateById(account);
        }
        return R.ok();
    }

    @Override
    @Transactional
    public R deleteAccount(Long id) {
        if (id == null || accountMapper.selectById(id) == null) return R.err("OCI 账号不存在");
        if (nodeMapper.countNodesUsingOciAccount(id) > 0) {
            return R.err("仍有节点绑定此 OCI 账号，请先解除节点绑定");
        }
        accountMapper.deleteById(id);
        return R.ok();
    }

    @Override
    public OciAccountTestResult testAccount(Long id) {
        OciAccount account = requireAccount(id);
        List<OciInstanceView> instances = ociCloudService.listInstances(account, decryptPrivateKey(account));
        return new OciAccountTestResult(true, instances.size());
    }

    @Override
    public List<OciInstanceView> listInstances(Long accountId) {
        OciAccount account = requireAccount(accountId);
        return ociCloudService.listInstances(account, decryptPrivateKey(account));
    }

    @Override
    public boolean accountExists(Long accountId) {
        return accountId != null && accountMapper.selectById(accountId) != null;
    }

    public OciAccount requireAccount(Long id) {
        if (id == null) throw new IllegalArgumentException("OCI 账号不存在");
        OciAccount account = accountMapper.selectById(id);
        if (account == null) throw new IllegalArgumentException("OCI 账号不存在");
        return account;
    }

    public String decryptPrivateKey(OciAccount account) {
        try {
            return crypto().decryptString(account.getPrivateKeyEncrypted());
        } catch (Exception e) {
            throw new IllegalStateException("OCI API 私钥无法解密，请重新保存账号");
        }
    }

    private AESCrypto crypto() {
        return EncryptionConfig.getOrCreateCrypto(encryptionSecret);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private OciConfig parseConfig(String configText) {
        if (!StringUtils.hasText(configText)) {
            throw new IllegalArgumentException("请粘贴 OCI config 内容，至少需要包含 [DEFAULT] 配置段");
        }

        Map<String, String> defaults = new LinkedHashMap<>();
        boolean inDefault = false;
        boolean foundDefault = false;
        for (String rawLine : configText.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;

            if (line.startsWith("[")) {
                if (!line.endsWith("]") || line.length() < 3) {
                    throw new IllegalArgumentException("OCI 配置分区格式无效，请检查方括号是否完整");
                }
                String section = line.substring(1, line.length() - 1).trim();
                if (section.isEmpty()) {
                    throw new IllegalArgumentException("OCI 配置分区名称不能为空");
                }
                inDefault = "DEFAULT".equalsIgnoreCase(section);
                foundDefault |= inDefault;
                continue;
            }

            if (!inDefault) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("OCI 配置格式无效，请检查是否为“名称=内容”格式");
            }

            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = stripInlineComment(line.substring(separator + 1)).trim();
            if (!key.isEmpty()) defaults.put(key, value);
        }

        if (!foundDefault) {
            throw new IllegalArgumentException("OCI 配置中没有找到 [DEFAULT] 配置段");
        }
        String userOcid = defaults.get("user");
        String fingerprint = defaults.get("fingerprint");
        String tenancyOcid = defaults.get("tenancy");
        String region = defaults.get("region");
        if (!StringUtils.hasText(userOcid) || !StringUtils.hasText(fingerprint)
                || !StringUtils.hasText(tenancyOcid) || !StringUtils.hasText(region)) {
            throw new IllegalArgumentException("[DEFAULT] 配置段缺少必填项，请检查 user、fingerprint、tenancy 和 region");
        }
        return new OciConfig(userOcid, tenancyOcid, fingerprint, region);
    }

    private String stripInlineComment(String value) {
        for (int i = 1; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current == '#' || current == ';') && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private record OciConfig(String userOcid, String tenancyOcid, String fingerprint, String region) {}
}
