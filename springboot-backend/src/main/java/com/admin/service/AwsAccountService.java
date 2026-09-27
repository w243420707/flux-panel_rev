package com.admin.service;

import com.admin.common.dto.AwsAccountSaveDto;
import com.admin.common.lang.R;

import java.util.List;
import java.util.Map;

public interface AwsAccountService {
    List<Map<String, Object>> listAccounts();
    R saveAccount(AwsAccountSaveDto dto);
    R deleteAccount(Long id);
    Map<String, String> getPrivateSecrets(Long id);
    Map<String, Object> testAccount(Long id, String region);
    boolean accountExists(Long id);
    Map<String, Object> quota(Long accountId, String region);
    List<Map<String, Object>> listAccountGroups();
    R saveAccountGroup(com.admin.common.dto.AwsAccountGroupSaveDto dto);
    R deleteAccountGroup(Long id);
    List<Map<String, Object>> listProxyPools();
    R saveProxyPool(com.admin.common.dto.AwsProxySaveDto dto);
    R deleteProxyPool(Long id);
    R importAccounts(com.admin.common.dto.AwsAccountImportDto dto);
    String encryptValue(String value);
    String decryptValue(String value);
}
