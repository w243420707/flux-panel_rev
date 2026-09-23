package com.admin.service;

import com.admin.common.dto.OciAccountSaveDto;
import com.admin.common.dto.OciAccountTestResult;
import com.admin.common.dto.OciInstanceView;
import com.admin.common.lang.R;

import java.util.List;
import java.util.Map;

public interface OciAccountService {
    List<Map<String, Object>> listAccounts();
    R saveAccount(OciAccountSaveDto dto);
    R deleteAccount(Long id);
    OciAccountTestResult testAccount(Long id);
    List<OciInstanceView> listInstances(Long accountId);
    boolean accountExists(Long accountId);
}
