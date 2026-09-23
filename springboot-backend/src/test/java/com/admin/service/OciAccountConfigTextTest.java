package com.admin.service;

import com.admin.common.dto.OciAccountSaveDto;
import com.admin.common.lang.R;
import com.admin.entity.OciAccount;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.OciAccountMapper;
import com.admin.service.impl.OciAccountServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OciAccountConfigTextTest {
    private static final String VALID_CONFIG = "[DEFAULT]\n"
            + "user=ocid1.user.oc1..user123\n"
            + "fingerprint=01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef\n"
            + "tenancy=ocid1.tenancy.oc1..tenancy123\n"
            + "region=eu-madrid-3\n"
            + "key_file=/not/read/by/panel.pem\n";

    private OciAccountMapper accounts;
    private OciAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        accounts = mock(OciAccountMapper.class);
        service = new OciAccountServiceImpl();
        ReflectionTestUtils.setField(service, "accountMapper", accounts);
        ReflectionTestUtils.setField(service, "nodeMapper", mock(NodeMapper.class));
        ReflectionTestUtils.setField(service, "encryptionSecret", "oci-account-test-secret");
    }

    @Test
    void parsesDefaultSectionWithCrLfWhitespaceCommentsAndIgnoresOtherKeysAndSections() {
        OciAccountSaveDto dto = dto("  Madrid account  ", "  ; account comment\r\n"
                + "[DEFAULT]\r\n"
                + "  user = ocid1.user.oc1..user123  # trailing text remains part of value\r\n"
                + "fingerprint = 01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef\r\n"
                + "tenancy = ocid1.tenancy.oc1..tenancy123\r\n"
                + "region = eu-madrid-3 ; region comment\r\n"
                + "key_file = /ignored/key.pem\r\n"
                + "# ignored comment\r\n"
                + "[OTHER_PROFILE]\r\n"
                + "user=ocid1.user.oc1..wrong\r\n", "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----");

        R result = service.saveAccount(dto);

        assertEquals(0, result.getCode());
        var saved = org.mockito.ArgumentCaptor.forClass(OciAccount.class);
        verify(accounts).insert(saved.capture());
        assertEquals("Madrid account", saved.getValue().getName());
        assertEquals("ocid1.user.oc1..user123", saved.getValue().getUserOcid());
        assertEquals("ocid1.tenancy.oc1..tenancy123", saved.getValue().getTenancyOcid());
        assertEquals("01:23:45:67:89:ab:cd:ef:01:23:45:67:89:ab:cd:ef", saved.getValue().getFingerprint());
        assertEquals("eu-madrid-3", saved.getValue().getRegion());
        assertNotNull(saved.getValue().getPrivateKeyEncrypted());
    }

    @Test
    void reportsMissingDefaultFieldsInPlainChineseAndDoesNotPersist() {
        OciAccountSaveDto dto = dto("Madrid", "[DEFAULT]\nuser=ocid1.user.oc1..user123\n", "valid PEM");

        R result = service.saveAccount(dto);

        assertEquals(-1, result.getCode());
        assertTrue(result.getMsg().contains("user、fingerprint、tenancy 和 region"));
        verify(accounts, never()).insert(any(OciAccount.class));
    }

    @Test
    void reportsMalformedConfigLineAndMissingDefaultSection() {
        OciAccountSaveDto malformed = dto("Madrid", "[DEFAULT]\nuser\n", "valid PEM");
        OciAccountSaveDto missingDefault = dto("Madrid", "[PROFILE]\nuser=x\n", "valid PEM");

        R malformedResult = service.saveAccount(malformed);
        R missingDefaultResult = service.saveAccount(missingDefault);

        assertTrue(malformedResult.getMsg().contains("名称=内容"));
        assertTrue(missingDefaultResult.getMsg().contains("[DEFAULT]"));
        verify(accounts, never()).insert(any(OciAccount.class));
    }

    @Test
    void editingWithBlankPrivateKeyKeepsThePreviouslyEncryptedKey() {
        OciAccount existing = new OciAccount();
        existing.setId(12L);
        existing.setPrivateKeyEncrypted("existing-encrypted-private-key");
        when(accounts.selectById(12L)).thenReturn(existing);
        OciAccountSaveDto dto = dto("Madrid", VALID_CONFIG, "   ");
        dto.setId(12L);

        R result = service.saveAccount(dto);

        assertEquals(0, result.getCode());
        var saved = org.mockito.ArgumentCaptor.forClass(OciAccount.class);
        verify(accounts).updateById(saved.capture());
        assertEquals("existing-encrypted-private-key", saved.getValue().getPrivateKeyEncrypted());
        assertEquals("ocid1.user.oc1..user123", saved.getValue().getUserOcid());
    }

    private static OciAccountSaveDto dto(String name, String configText, String privateKey) {
        OciAccountSaveDto dto = new OciAccountSaveDto();
        dto.setName(name);
        dto.setConfigText(configText);
        dto.setPrivateKey(privateKey);
        return dto;
    }
}
