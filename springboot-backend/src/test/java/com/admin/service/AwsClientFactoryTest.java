package com.admin.service;

import com.admin.entity.AwsAccount;
import com.admin.service.impl.AwsClientFactory;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import java.time.Instant;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

class AwsClientFactoryTest {
    @Test
    void buildsEc2AndStsClientsWithStaticSessionCredentials() {
        AwsClientFactory factory = new AwsClientFactory();
        AwsAccount account = new AwsAccount();
        account.setAccessKeyId("AKIA1234567890TEST");
        account.setRegion("us-east-1");

        var credentialsProvider = factory.credentials(account.getAccessKeyId(), "secret-value-123456", "session-token");
        AwsCredentials credentials = credentialsProvider.resolveCredentials();
        assertInstanceOf(AwsSessionCredentials.class, credentials);
        assertEquals("session-token", ((AwsSessionCredentials) credentials).sessionToken());

        try (Ec2Client ec2 = factory.ec2(account, "secret-value-123456", "session-token", null);
             StsClient sts = factory.sts(account, "secret-value-123456", "session-token", "us-west-2")) {
            assertNotNull(ec2);
            assertNotNull(sts);
            assertEquals("us-east-1", ec2.serviceClientConfiguration().region().id());
            assertEquals("us-west-2", sts.serviceClientConfiguration().region().id());
        }
    }

    @Test
    void rejectsMalformedProxyBeforeOpeningClient() {
        AwsClientFactory factory = new AwsClientFactory();
        AwsAccount account = new AwsAccount();
        account.setAccessKeyId("AKIA1234567890TEST");
        account.setRegion("us-east-1");
        account.setProxy("not-a-uri");
        assertThrows(IllegalArgumentException.class,
                () -> factory.ec2(account, "secret-value-123456", "", null));
    }

    @Test
    void usesFiniteTimeoutAndRetryPolicy() {
        var settings = new AwsClientFactory().overrides();
        assertEquals(java.time.Duration.ofSeconds(30), settings.apiCallTimeout().orElseThrow());
        assertEquals(java.time.Duration.ofSeconds(12), settings.apiCallAttemptTimeout().orElseThrow());
        assertEquals(2, settings.retryPolicy().orElseThrow().numRetries());
    }

    @Test
    void mfaSessionsAreCachedAcrossRegionsAndClearedOnAccountEdit() {
        StsClient sts = mock(StsClient.class);
        when(sts.getSessionToken(any(GetSessionTokenRequest.class))).thenReturn(GetSessionTokenResponse.builder().credentials(temporary()).build());
        AwsClientFactory factory = stubFactory(sts); AwsAccount account = account();
        factory.credentialsForAccount(account, "secret-value", "", "us-east-1");
        factory.credentialsForAccount(account, "secret-value", "", "eu-west-1");
        verify(sts, times(1)).getSessionToken(any(GetSessionTokenRequest.class));
        factory.invalidate(account.getId()); factory.credentialsForAccount(account, "secret-value", "", "us-east-1");
        verify(sts, times(2)).getSessionToken(any(GetSessionTokenRequest.class));
    }

    @Test
    void assumesRoleWithMfaOnceWithoutFirstGettingDuplicateSession() {
        StsClient sts = mock(StsClient.class);
        when(sts.assumeRole(any(AssumeRoleRequest.class))).thenReturn(AssumeRoleResponse.builder().credentials(temporary()).build());
        AwsClientFactory factory = stubFactory(sts); AwsAccount account = account(); account.setRoleArn("arn:aws:iam::123456789012:role/test");
        AwsCredentials credentials = factory.credentialsForAccount(account, "secret-value", "", "us-east-1").resolveCredentials();
        assertInstanceOf(AwsSessionCredentials.class, credentials);
        var capture = org.mockito.ArgumentCaptor.forClass(AssumeRoleRequest.class); verify(sts).assumeRole(capture.capture());
        assertEquals("123456", capture.getValue().tokenCode());
        verify(sts, never()).getSessionToken(any(GetSessionTokenRequest.class));
    }

    @Test
    void rejectsExpiredStsSessionInsteadOfCachingIt() {
        StsClient sts = mock(StsClient.class);
        when(sts.getSessionToken(any(GetSessionTokenRequest.class))).thenReturn(GetSessionTokenResponse.builder().credentials(temporary().toBuilder().expiration(Instant.now().minusSeconds(60)).build()).build());
        assertThrows(IllegalStateException.class, () -> stubFactory(sts).credentialsForAccount(account(), "secret-value", "", "us-east-1"));
    }

    @Test
    void acceptsAccountScopedHttpAndSocksProxiesWithoutChangingGlobalProperties() {
        String previous = System.getProperty("socksProxyHost"); AwsAccount account = account(); account.setMfaSerialNumber(null);
        for (String proxy : java.util.List.of("http://user:pass@127.0.0.1:8080", "socks5://user:pass@127.0.0.1:1080", "https://127.0.0.1:443")) {
            account.setProxyResolved(proxy);
            try (Ec2Client ignored = new AwsClientFactory().ec2(account, "secret-value", "", null)) { assertEquals(previous, System.getProperty("socksProxyHost")); }
        }
        assertThrows(IllegalArgumentException.class, () -> AwsClientFactory.validateProxy("ftp://secret:password@localhost:21"));
        assertThrows(IllegalArgumentException.class, () -> AwsClientFactory.validateProxy("http://localhost:80/path?secret=token"));
    }

    private AwsClientFactory stubFactory(StsClient sts) {
        return new AwsClientFactory() {
            @Override protected StsClient baseSts(AwsAccount account, AwsCredentialsProvider credentials, String region) { return sts; }
        };
    }
    private AwsAccount account() {
        AwsAccount account = new AwsAccount(); account.setId(1L); account.setAccessKeyId("AKIA1234567890TEST"); account.setRegion("us-east-1");
        account.setMfaSerialNumber("arn:aws:iam::123456789012:mfa/test"); account.setMfaTokenCode("123456"); return account;
    }
    private Credentials temporary() {
        return Credentials.builder().accessKeyId("ASIA1234567890TEST").secretAccessKey("temporary-secret").sessionToken("temporary-session").expiration(Instant.now().plusSeconds(3600)).build();
    }
}
