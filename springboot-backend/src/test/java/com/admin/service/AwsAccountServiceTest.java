package com.admin.service;

import com.admin.common.dto.AwsAccountSaveDto;
import com.admin.common.lang.R;
import com.admin.entity.AwsAccount;
import com.admin.mapper.AwsAccountMapper;
import com.admin.mapper.AwsEc2InstanceProfileMapper;
import com.admin.mapper.AwsEc2InstanceTemplateMapper;
import com.admin.mapper.AwsNodeBindingMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.AwsAccountGroupMapper;
import com.admin.mapper.AwsProxyPoolMapper;
import com.admin.mapper.AwsFailoverConfigMapper;
import com.admin.mapper.AwsOperationMapper;
import com.admin.mapper.AwsFailoverJobMapper;
import com.admin.service.impl.AwsAccountServiceImpl;
import com.admin.service.impl.AwsClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AwsAccountServiceTest {
    private AwsAccountMapper mapper;
    private AwsAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AwsAccountMapper.class);
        service = new AwsAccountServiceImpl();
        ReflectionTestUtils.setField(service, "accountMapper", mapper);
        ReflectionTestUtils.setField(service, "profileMapper", mock(AwsEc2InstanceProfileMapper.class));
        ReflectionTestUtils.setField(service, "templateMapper", mock(AwsEc2InstanceTemplateMapper.class));
        ReflectionTestUtils.setField(service, "bindingMapper", mock(AwsNodeBindingMapper.class));
        ReflectionTestUtils.setField(service, "nodeMapper", mock(NodeMapper.class));
        ReflectionTestUtils.setField(service, "groupMapper", mock(AwsAccountGroupMapper.class));
        ReflectionTestUtils.setField(service, "proxyMapper", mock(AwsProxyPoolMapper.class));
        ReflectionTestUtils.setField(service, "failoverMapper", mock(AwsFailoverConfigMapper.class));
        ReflectionTestUtils.setField(service, "operationMapper", mock(AwsOperationMapper.class));
        ReflectionTestUtils.setField(service, "failoverJobMapper", mock(AwsFailoverJobMapper.class));
        ReflectionTestUtils.setField(service, "clientFactory", mock(AwsClientFactory.class));
        ReflectionTestUtils.setField(service, "encryptionSecret", "aws-account-test-secret");
    }

    @Test
    void savesSecretsEncryptedAndNeverInPlaintext() {
        AwsAccountSaveDto dto = new AwsAccountSaveDto();
        dto.setName("test-account");
        dto.setAccessKeyId("AKIA1234567890TEST");
        dto.setSecretAccessKey("secret-value-123456");
        dto.setMfaSecret("JBSWY3DPEHPK3PXP");
        dto.setMfaSerial("arn:aws:iam::123456789012:mfa/test");
        dto.setRegion("us-east-1");

        R result = service.saveAccount(dto);

        assertEquals(0, result.getCode());
        var captured = org.mockito.ArgumentCaptor.forClass(AwsAccount.class);
        verify(mapper).insert(captured.capture());
        AwsAccount saved = captured.getValue();
        assertNotEquals("secret-value-123456", saved.getAccessSecretEncrypted());
        assertEquals("****TEST", saved.getAccessKeyId());
        assertNotEquals(dto.getAccessKeyId(), saved.getAccessKeyEncrypted());
        assertEquals(dto.getAccessKeyId(), service.decryptAccessKey(saved));
        assertNotEquals("JBSWY3DPEHPK3PXP", saved.getMfaSecretEncrypted());
        assertEquals("secret-value-123456", service.decryptSecret(saved));
        assertEquals("JBSWY3DPEHPK3PXP", service.decryptMfaSecret(saved));
    }

    @Test
    void editingWithBlankSecretKeepsPreviousCiphertext() {
        AwsAccount existing = new AwsAccount();
        existing.setId(7L);
        existing.setAccessSecretEncrypted("previous-ciphertext");
        when(mapper.selectById(7L)).thenReturn(existing);

        AwsAccountSaveDto dto = new AwsAccountSaveDto();
        dto.setId(7L);
        dto.setName("edited");
        dto.setAccessKeyId("AKIA1234567890TEST");
        dto.setRegion("us-east-1");

        R result = service.saveAccount(dto);

        assertEquals(0, result.getCode());
        var captured = org.mockito.ArgumentCaptor.forClass(AwsAccount.class);
        verify(mapper).updateById(captured.capture());
        assertEquals("previous-ciphertext", captured.getValue().getAccessSecretEncrypted());
    }

    @Test
    void rejectsMissingSecretForNewAccountWithoutWriting() {
        AwsAccountSaveDto dto = new AwsAccountSaveDto();
        dto.setName("test-account");
        dto.setAccessKeyId("AKIA1234567890TEST");
        dto.setRegion("us-east-1");

        R result = service.saveAccount(dto);

        assertEquals(-1, result.getCode());
        verify(mapper, never()).insert(any(AwsAccount.class));
    }

    @Test
    void refusesSavingWithoutIndependentEncryptionKey() {
        ReflectionTestUtils.setField(service, "encryptionSecret", "");
        AwsAccountSaveDto dto = validAccount();
        assertEquals(-1, service.saveAccount(dto).getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void encryptsSessionAndProxyCredentialsAndDoesNotReturnThemInList() {
        AwsAccountSaveDto dto = validAccount(); dto.setSessionToken("temporary-token"); dto.setProxy("socks5://user:private-password@127.0.0.1:1080");
        assertEquals(0, service.saveAccount(dto).getCode());
        var capture = org.mockito.ArgumentCaptor.forClass(AwsAccount.class); verify(mapper).insert(capture.capture());
        AwsAccount account = capture.getValue();
        assertEquals("socks5://127.0.0.1:1080", account.getProxy()); assertFalse(account.getProxyEncrypted().contains("private-password"));
        assertEquals("temporary-token", service.decryptSessionToken(account)); assertEquals(dto.getProxy(), service.decryptProxy(account));
        when(mapper.selectList(any())).thenReturn(java.util.List.of(account));
        String serialized = com.alibaba.fastjson2.JSON.toJSONString(service.listAccounts());
        assertFalse(serialized.contains("private-password")); assertFalse(serialized.contains("temporary-token"));
        assertFalse(serialized.contains("secret-value")); assertFalse(serialized.contains("AKIA1234567890TEST"));
    }

    @Test
    void refusesMfaWithAlreadyTemporaryCredentials() {
        AwsAccountSaveDto dto = validAccount(); dto.setSessionToken("temporary-token"); dto.setMfaSerial("arn:aws:iam::123456789012:mfa/test"); dto.setMfaSecret("JBSWY3DPEHPK3PXP");
        assertEquals(-1, service.saveAccount(dto).getCode()); verify(mapper, never()).insert(any());
    }

    @Test
    void blocksDeletingAnAccountStillBoundDirectlyToANode() {
        AwsAccount account = new AwsAccount(); account.setId(3L); when(mapper.selectById(3L)).thenReturn(account);
        NodeMapper nodes = mock(NodeMapper.class); when(nodes.selectCount(any())).thenReturn(1); ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        assertEquals(-1, service.deleteAccount(3L).getCode()); verify(mapper, never()).deleteById(any());
    }

    @Test
    void parsesWhitespaceImportWithoutReflectingBadSecrets() {
        var parsed = AwsAccountServiceImpl.parseImportLine("AKIA1234567890TEST   secret-value-123456 us-west-2 west", "us-east-1");
        assertEquals("us-west-2", parsed.getRegion()); assertEquals("west", parsed.getName());
        assertEquals("us-east-1", AwsAccountServiceImpl.parseImportLine("AKIA1234567890TEST secret-value-123456", "us-east-1").getRegion());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> AwsAccountServiceImpl.parseImportLine("secret-to-not-echo", "us-east-1"));
        assertFalse(error.getMessage().contains("secret-to-not-echo"));
    }

    @Test
    void generatesKnownRfcTotpValuesAndRejectsInvalidBase32() {
        assertEquals("287082", AwsAccountServiceImpl.generateTotp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", java.time.Instant.ofEpochSecond(59)));
        assertThrows(IllegalArgumentException.class, () -> AwsAccountServiceImpl.generateTotp("not-a-valid-secret", java.time.Instant.now()));
    }

    @Test
    void refusedQuotaReadNeverBecomesZeroQuota() {
        AwsAccount account = new AwsAccount(); account.setId(3L); account.setAccessKeyEncrypted(service.encryptValue("AKIA1234567890TEST")); account.setAccessSecretEncrypted(service.encryptValue("secret-value")); account.setRegion("us-east-1");
        when(mapper.selectById(3L)).thenReturn(account);
        AwsClientFactory factory = mock(AwsClientFactory.class); ReflectionTestUtils.setField(service, "clientFactory", factory);
        when(factory.serviceQuotas(any(), any(), any(), any())).thenThrow(new IllegalStateException("AccessDenied secret-sensitive-url"));
        var failure = assertThrows(IllegalStateException.class, () -> service.quota(3L, "us-east-1"));
        assertFalse(failure.getMessage().contains("secret-sensitive-url"));
    }

    @Test
    void groupAndProxyDeletionRejectReferencedResources() {
        AwsAccountGroupMapper groups = mock(AwsAccountGroupMapper.class); com.admin.entity.AwsAccountGroup group = new com.admin.entity.AwsAccountGroup(); group.setId(1L); when(groups.selectById(1L)).thenReturn(group); ReflectionTestUtils.setField(service, "groupMapper", groups);
        AwsProxyPoolMapper proxies = mock(AwsProxyPoolMapper.class); com.admin.entity.AwsProxyPool proxy = new com.admin.entity.AwsProxyPool(); proxy.setId(2L); when(proxies.selectById(2L)).thenReturn(proxy); ReflectionTestUtils.setField(service, "proxyMapper", proxies);
        when(mapper.selectCount(any())).thenReturn(1);
        assertEquals(-1, service.deleteAccountGroup(1L).getCode()); assertEquals(-1, service.deleteProxyPool(2L).getCode());
        verify(groups, never()).deleteById(any()); verify(proxies, never()).deleteById(any());
    }

    @Test
    void emptySecretEditsKeepAccessKeySessionAndProxyReferences() {
        AwsAccount account = new AwsAccount(); account.setId(7L); account.setAccessKeyId("****TEST"); account.setAccessKeyEncrypted("previous-key"); account.setAccessSecretEncrypted("previous-secret"); account.setSessionTokenEncrypted("previous-token"); account.setProxyPoolId(5L); when(mapper.selectById(7L)).thenReturn(account);
        var dto = validAccount(); dto.setId(7L); dto.setAccessKeyId("****TEST"); dto.setSecretAccessKey("");
        assertEquals(0, service.saveAccount(dto).getCode()); assertEquals("previous-key", account.getAccessKeyEncrypted()); assertEquals("previous-secret", account.getAccessSecretEncrypted()); assertEquals("previous-token", account.getSessionTokenEncrypted()); assertEquals(5L, account.getProxyPoolId());
    }

    private AwsAccountSaveDto validAccount() {
        AwsAccountSaveDto dto = new AwsAccountSaveDto(); dto.setName("test-account"); dto.setAccessKeyId("AKIA1234567890TEST"); dto.setSecretAccessKey("secret-value-123456"); dto.setRegion("us-east-1"); return dto;
    }
}
