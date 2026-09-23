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
        String userOcid = trim(dto.getUserOcid());
        String tenancyOcid = trim(dto.getTenancyOcid());
        String fingerprint = trim(dto.getFingerprint());
        String region = trim(dto.getRegion());
        String privateKey = dto.getPrivateKey() == null ? "" : dto.getPrivateKey().trim();
        if (!StringUtils.hasText(name) || name.length() > 120
                || !userOcid.startsWith("ocid1.user.") || !tenancyOcid.startsWith("ocid1.tenancy.")
                || !FINGERPRINT.matcher(fingerprint).matches() || !REGION.matcher(region).matches()) {
            return R.err("请检查账号名称、User OCID、Tenancy OCID、指纹和区域格式");
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
        account.setUserOcid(userOcid);
        account.setTenancyOcid(tenancyOcid);
        account.setFingerprint(fingerprint);
        account.setRegion(region);
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
}
