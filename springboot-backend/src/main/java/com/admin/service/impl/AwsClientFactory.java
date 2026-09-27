package com.admin.service.impl;

import com.admin.entity.AwsAccount;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.ssl.SSLContexts;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Account-scoped networking and short-lived credentials; never changes JVM global proxy settings. */
@Component
public class AwsClientFactory {
    private static final int MAX_SESSIONS = 256;
    private final Map<String, CachedSession> sessions = new LinkedHashMap<>(16, .75f, true);
    private final Object[] locks = new Object[32];
    public AwsClientFactory() { Arrays.setAll(locks, i -> new Object()); }

    public Ec2Client ec2(AwsAccount account, String secret, String token, String region) {
        String selected = region(account, region);
        return Ec2Client.builder().region(Region.of(selected)).credentialsProvider(credentialsForAccount(account, secret, token, selected))
                .overrideConfiguration(overrides()).httpClientBuilder(httpClientBuilder(account)).build();
    }
    public StsClient sts(AwsAccount account, String secret, String token, String region) {
        String selected = region(account, region);
        return baseSts(account, credentialsForAccount(account, secret, token, selected), selected);
    }
    public IamClient iam(AwsAccount account, String secret, String token, String region) {
        String selected = region(account, region);
        return IamClient.builder().region(Region.of(selected)).credentialsProvider(credentialsForAccount(account, secret, token, selected))
                .overrideConfiguration(overrides()).httpClientBuilder(httpClientBuilder(account)).build();
    }
    public ServiceQuotasClient serviceQuotas(AwsAccount account, String secret, String token, String region) {
        String selected = region(account, region);
        return ServiceQuotasClient.builder().region(Region.of(selected)).credentialsProvider(credentialsForAccount(account, secret, token, selected))
                .overrideConfiguration(overrides()).httpClientBuilder(httpClientBuilder(account)).build();
    }
    public ClientOverrideConfiguration overrides() {
        return ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(12))
                .retryPolicy(RetryPolicy.builder().numRetries(2).build()).build();
    }
    public AwsCredentialsProvider credentials(String key, String secret, String token) {
        if (!StringUtils.hasText(key) || key.contains("*") || !StringUtils.hasText(secret)) throw new IllegalArgumentException("AWS Access Key 和 Secret Access Key 不能为空");
        return StaticCredentialsProvider.create(StringUtils.hasText(token)
                ? AwsSessionCredentials.create(key.trim(), secret, token) : AwsBasicCredentials.create(key.trim(), secret));
    }
    public AwsCredentialsProvider credentialsForAccount(AwsAccount account, String secret, String token, String region) {
        String key = StringUtils.hasText(account.getAccessKeyResolved()) ? account.getAccessKeyResolved() : account.getAccessKeyId();
        AwsCredentialsProvider base = credentials(key, secret, token);
        boolean mfa = StringUtils.hasText(account.getMfaSerialNumber()), role = StringUtils.hasText(account.getRoleArn());
        if (!mfa && !role) return base;
        if (mfa && StringUtils.hasText(token)) throw new IllegalArgumentException("已有 Session Token 的临时凭据不能再次申请 MFA 会话");
        String cacheKey = digest(String.join("\n", String.valueOf(account.getId()), key, secret, Objects.toString(token, ""),
                Objects.toString(account.getMfaSerialNumber(), ""), Objects.toString(account.getMfaSecretEncrypted(), ""),
                Objects.toString(account.getRoleArn(), ""), Objects.toString(account.getRoleSessionName(), ""), Objects.toString(account.getRoleExternalId(), ""),
                Objects.toString(account.getProxyResolved(), ""), Objects.toString(account.getUpdatedTime(), "")));
        synchronized (locks[(cacheKey.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            synchronized (sessions) {
                CachedSession cached = sessions.get(cacheKey);
                if (cached != null && cached.expires.isAfter(Instant.now().plusSeconds(60))) return cached.provider;
            }
            if (mfa && !Objects.toString(account.getMfaTokenCode(), "").matches("[0-9]{6}")) throw new IllegalArgumentException("AWS MFA 验证码不可用");
            Credentials temporary;
            try (StsClient sts = baseSts(account, base, region)) {
                if (role) {
                    AssumeRoleRequest.Builder request = AssumeRoleRequest.builder().roleArn(account.getRoleArn()).durationSeconds(3600)
                            .roleSessionName(StringUtils.hasText(account.getRoleSessionName()) ? account.getRoleSessionName() : "flux-panel");
                    if (StringUtils.hasText(account.getRoleExternalId())) request.externalId(account.getRoleExternalId());
                    if (mfa) request.serialNumber(account.getMfaSerialNumber()).tokenCode(account.getMfaTokenCode());
                    temporary = sts.assumeRole(request.build()).credentials();
                } else {
                    temporary = sts.getSessionToken(GetSessionTokenRequest.builder().serialNumber(account.getMfaSerialNumber())
                            .tokenCode(account.getMfaTokenCode()).durationSeconds(3600).build()).credentials();
                }
            }
            if (temporary == null || !StringUtils.hasText(temporary.accessKeyId()) || !StringUtils.hasText(temporary.secretAccessKey())
                    || !StringUtils.hasText(temporary.sessionToken()) || temporary.expiration() == null || !temporary.expiration().isAfter(Instant.now()))
                throw new IllegalStateException("AWS STS 未返回有效的临时凭据");
            AwsCredentialsProvider provider = StaticCredentialsProvider.create(AwsSessionCredentials.create(temporary.accessKeyId(), temporary.secretAccessKey(), temporary.sessionToken()));
            synchronized (sessions) {
                sessions.entrySet().removeIf(entry -> entry.getValue().expires.isBefore(Instant.now()));
                while (sessions.size() >= MAX_SESSIONS) sessions.remove(sessions.keySet().iterator().next());
                sessions.put(cacheKey, new CachedSession(account.getId(), temporary.expiration(), provider));
            }
            return provider;
        }
    }
    protected StsClient baseSts(AwsAccount account, AwsCredentialsProvider credentials, String region) {
        return StsClient.builder().region(Region.of(region)).credentialsProvider(credentials).overrideConfiguration(overrides())
                .httpClientBuilder(httpClientBuilder(account)).build();
    }
    public void invalidate(Long accountId) { synchronized (sessions) { sessions.entrySet().removeIf(entry -> Objects.equals(accountId, entry.getValue().accountId)); } }
    public void invalidateAll() { synchronized (sessions) { sessions.clear(); } }
    private static String region(AwsAccount account, String region) {
        String value = StringUtils.hasText(region) ? region.trim() : account.getRegion();
        if (value == null || !value.matches("^[a-z]{2,3}(-gov)?-[a-z0-9-]+-[0-9]+$")) throw new IllegalArgumentException("AWS 区域格式无效"); return value;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("AWS 临时会话初始化失败"); }
    }
    public static URI validateProxy(String value) {
        try {
            if (value == null || value.length() > 4096) throw new IllegalArgumentException();
            URI uri = URI.create(value.trim());
            if (!Set.of("http", "https", "socks5", "socks5h", "socks").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
                    || !StringUtils.hasText(uri.getHost()) || uri.getPort() < 1 || uri.getPort() > 65535
                    || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath())) || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            if (uri.getRawUserInfo() != null) {
                String[] authentication = uri.getRawUserInfo().split(":", 2);
                if (authentication.length != 2 || decoded(authentication[0]).isEmpty() || decoded(authentication[0]).getBytes(StandardCharsets.UTF_8).length > 255 || decoded(authentication[1]).getBytes(StandardCharsets.UTF_8).length > 255) throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception e) { throw new IllegalArgumentException("代理格式应为 http(s)://[用户:密码@]主机:端口 或 socks5://[用户:密码@]主机:端口"); }
    }
    private ApacheHttpClient.Builder httpClientBuilder(AwsAccount account) {
        ApacheHttpClient.Builder builder = ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(10))
                .connectionAcquisitionTimeout(Duration.ofSeconds(5)).maxConnections(16).connectionMaxIdleTime(Duration.ofSeconds(30));
        String proxy = StringUtils.hasText(account.getProxyResolved()) ? account.getProxyResolved() : account.getProxy();
        ProxyConfiguration.Builder config = ProxyConfiguration.builder().useSystemPropertyValues(false).useEnvironmentVariableValues(false);
        if (!StringUtils.hasText(proxy)) return builder.proxyConfiguration(config.build());
        URI uri = validateProxy(proxy);
        if (uri.getScheme().toLowerCase(Locale.ROOT).startsWith("socks")) return builder.proxyConfiguration(config.build()).socketFactory(new SocksTlsSocketFactory(uri));
        try { config.endpoint(new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null)); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("AWS 代理格式无效"); }
        if (uri.getRawUserInfo() != null) { String[] pair = uri.getRawUserInfo().split(":", 2); config.username(decoded(pair[0])).password(decoded(pair[1])); }
        return builder.proxyConfiguration(config.build());
    }
    private static String decoded(String value) { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8); }
    private record CachedSession(Long accountId, Instant expires, AwsCredentialsProvider provider) {}

    /** A private SOCKS5 tunnel per account, including RFC1929 auth, followed by normal verified TLS. */
    static final class SocksTlsSocketFactory extends SSLConnectionSocketFactory {
        private final URI proxy;
        SocksTlsSocketFactory(URI proxy) { super(SSLContexts.createSystemDefault(), getDefaultHostnameVerifier()); this.proxy = proxy; }
        @Override public Socket createSocket(org.apache.http.protocol.HttpContext context) { return new Socket(Proxy.NO_PROXY); }
        @Override public Socket connectSocket(int timeout, Socket socket, org.apache.http.HttpHost host, InetSocketAddress remote,
                                               InetSocketAddress local, org.apache.http.protocol.HttpContext context) throws IOException {
            Socket tunnel = socket == null ? createSocket(context) : socket;
            try {
                if (local != null) tunnel.bind(local);
                tunnel.connect(new InetSocketAddress(proxy.getHost(), proxy.getPort()), timeout > 0 ? timeout : 5000); tunnel.setSoTimeout(10000);
                InputStream input = tunnel.getInputStream(); OutputStream output = tunnel.getOutputStream();
                boolean auth = proxy.getRawUserInfo() != null;
                output.write(new byte[] {5, 1, (byte) (auth ? 2 : 0)}); output.flush();
                if (input.read() != 5 || input.read() != (auth ? 2 : 0)) throw new IOException("AWS SOCKS 代理不支持所需认证方式");
                if (auth) {
                    String[] pair = proxy.getRawUserInfo().split(":", 2); byte[] user = decoded(pair[0]).getBytes(StandardCharsets.UTF_8), password = decoded(pair[1]).getBytes(StandardCharsets.UTF_8);
                    output.write(1); output.write(user.length); output.write(user); output.write(password.length); output.write(password); output.flush();
                    if (input.read() != 1 || input.read() != 0) throw new IOException("AWS SOCKS 代理认证失败");
                }
                byte[] destination = IDN.toASCII(host.getHostName()).getBytes(StandardCharsets.US_ASCII);
                if (destination.length == 0 || destination.length > 255) throw new IOException("AWS SOCKS 目标地址无效");
                int port = remote.getPort(); output.write(new byte[] {5, 1, 0, 3, (byte) destination.length}); output.write(destination); output.write(port >> 8); output.write(port & 255); output.flush();
                if (input.read() != 5 || input.read() != 0 || input.read() != 0) throw new IOException("AWS SOCKS 连接失败");
                int type = input.read(); int length = type == 1 ? 4 : type == 4 ? 16 : type == 3 ? input.read() : -1;
                if (length < 0 || input.readNBytes(length + 2).length != length + 2) throw new IOException("AWS SOCKS 响应无效");
                return createLayeredSocket(tunnel, host.getHostName(), port, context);
            } catch (IOException | RuntimeException e) { try { tunnel.close(); } catch (IOException ignored) {} throw e; }
        }
    }
}
