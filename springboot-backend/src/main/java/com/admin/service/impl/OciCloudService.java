package com.admin.service.impl;

import com.admin.common.dto.OciInstanceView;
import com.admin.entity.OciAccount;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreatePublicIpDetails;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.PrivateIp;
import com.oracle.bmc.core.model.PublicIp;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.CreatePublicIpRequest;
import com.oracle.bmc.core.requests.DeletePublicIpRequest;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.core.requests.GetPublicIpByIpAddressRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.ListPrivateIpsRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.responses.GetInstanceResponse;
import com.oracle.bmc.core.responses.GetPublicIpByIpAddressResponse;
import com.oracle.bmc.core.responses.GetVnicResponse;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.core.responses.ListPrivateIpsResponse;
import com.oracle.bmc.core.responses.ListVnicAttachmentsResponse;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.identity.responses.ListCompartmentsResponse;
import com.oracle.bmc.retrier.RetryConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OciCloudService {
    private static final long DETACH_TIMEOUT_MILLIS = 24_000L;
    private static final long DETACH_POLL_MILLIS = 2_000L;

    public List<OciInstanceView> listInstances(OciAccount account, String privateKey) {
        try (OciClients clients = openClients(account, privateKey)) {
            List<String> compartments = listCompartments(clients.identity, account.getTenancyOcid());
            List<OciInstanceView> result = new ArrayList<>();
            for (String compartmentId : compartments) {
                for (Instance instance : listInstances(clients.compute, compartmentId)) {
                    if (instance.getLifecycleState() == Instance.LifecycleState.Terminated) {
                        continue;
                    }
                    String publicIp = null;
                    VnicAttachment primaryAttachment = findPrimaryAttachment(
                            clients.compute, compartmentId, instance.getId());
                    if (primaryAttachment != null) {
                        Vnic vnic = clients.network.getVnic(GetVnicRequest.builder()
                                .vnicId(primaryAttachment.getVnicId()).build()).getVnic();
                        publicIp = vnic.getPublicIp();
                    }
                    result.add(new OciInstanceView(instance.getId(), safeDisplayName(instance),
                            publicIp, String.valueOf(instance.getLifecycleState())));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("OCI instance discovery failed, accountId={}, errorType={}",
                    account.getId(), e.getClass().getSimpleName());
            throw new IllegalArgumentException("OCI 连接失败，请检查账号信息、区域和访问策略");
        }
    }

    public String changePublicIp(OciAccount account, String privateKey, String instanceOcid) {
        try (OciClients clients = openClients(account, privateKey)) {
            Instance instance = clients.compute.getInstance(GetInstanceRequest.builder()
                    .instanceId(instanceOcid).build()).getInstance();
            if (instance.getLifecycleState() == Instance.LifecycleState.Terminated) {
                throw new IllegalStateException("OCI 实例已终止");
            }

            VnicAttachment attachment = findPrimaryAttachment(
                    clients.compute, instance.getCompartmentId(), instanceOcid);
            if (attachment == null) {
                throw new IllegalStateException("OCI 实例没有已连接的主网卡");
            }
            Vnic vnic = clients.network.getVnic(GetVnicRequest.builder()
                    .vnicId(attachment.getVnicId()).build()).getVnic();
            PrivateIp primaryPrivateIp = findPrimaryPrivateIp(clients.network, vnic.getId());
            if (primaryPrivateIp == null) {
                throw new IllegalStateException("OCI 主网卡没有可用的主私有 IP");
            }

            String currentPublicIp = vnic.getPublicIp();
            if (currentPublicIp != null && !currentPublicIp.trim().isEmpty()) {
                PublicIp publicIp = getPublicIp(clients.network, currentPublicIp);
                if (publicIp == null) {
                    throw new IllegalStateException("OCI 当前公网 IP 信息不可用");
                }
                if (publicIp.getLifetime() == PublicIp.Lifetime.Reserved) {
                    throw new IllegalStateException("当前是 OCI 保留型公网 IP，不会自动删除");
                }
                clients.network.deletePublicIp(DeletePublicIpRequest.builder()
                        .publicIpId(publicIp.getId())
                        .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                        .build());
                waitUntilDetached(clients.network, currentPublicIp);
            }

            String displayName = safeDisplayName(instance).replaceAll("[^A-Za-z0-9_-]", "-");
            CreatePublicIpDetails details = CreatePublicIpDetails.builder()
                    .compartmentId(instance.getCompartmentId())
                    .displayName("flux-panel-" + displayName.substring(0, Math.min(40, displayName.length())))
                    .lifetime(CreatePublicIpDetails.Lifetime.Ephemeral)
                    .privateIpId(primaryPrivateIp.getId())
                    .build();
            PublicIp created = clients.network.createPublicIp(CreatePublicIpRequest.builder()
                    .createPublicIpDetails(details)
                    .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION)
                    .build()).getPublicIp();
            return created.getIpAddress();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OCI public IP change failed, instanceOcidSuffix={}, errorType={}",
                    ocidSuffix(instanceOcid), e.getClass().getSimpleName());
            throw new IllegalStateException("OCI 公网 IP 更换失败");
        }
    }

    private OciClients openClients(OciAccount account, String privateKey) {
        byte[] keyBytes = privateKey.getBytes(StandardCharsets.UTF_8);
        SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                .userId(account.getUserOcid())
                .tenantId(account.getTenancyOcid())
                .fingerprint(account.getFingerprint())
                .region(Region.fromRegionId(account.getRegion()))
                .privateKeySupplier(() -> new ByteArrayInputStream(keyBytes))
                .build();
        ClientConfiguration configuration = ClientConfiguration.builder()
                .connectionTimeoutMillis(10_000)
                .readTimeoutMillis(30_000)
                .build();
        return new OciClients(
                IdentityClient.builder().configuration(configuration).build(provider),
                ComputeClient.builder().configuration(configuration).build(provider),
                VirtualNetworkClient.builder().configuration(configuration).build(provider));
    }

    private List<String> listCompartments(IdentityClient identity, String tenancyId) {
        List<String> result = new ArrayList<>();
        result.add(tenancyId);
        String page = null;
        do {
            ListCompartmentsRequest.Builder builder = ListCompartmentsRequest.builder()
                    .compartmentId(tenancyId)
                    .compartmentIdInSubtree(true)
                    .accessLevel(ListCompartmentsRequest.AccessLevel.Accessible)
                    .lifecycleState(Compartment.LifecycleState.Active);
            if (page != null) builder.page(page);
            ListCompartmentsResponse response = identity.listCompartments(builder.build());
            for (Compartment compartment : response.getItems()) result.add(compartment.getId());
            page = response.getOpcNextPage();
        } while (page != null);
        return result;
    }

    private List<Instance> listInstances(ComputeClient compute, String compartmentId) {
        List<Instance> result = new ArrayList<>();
        String page = null;
        do {
            ListInstancesRequest.Builder builder = ListInstancesRequest.builder().compartmentId(compartmentId);
            if (page != null) builder.page(page);
            ListInstancesResponse response = compute.listInstances(builder.build());
            result.addAll(response.getItems());
            page = response.getOpcNextPage();
        } while (page != null);
        return result;
    }

    private VnicAttachment findPrimaryAttachment(ComputeClient compute, String compartmentId, String instanceOcid) {
        String page = null;
        VnicAttachment fallback = null;
        do {
            ListVnicAttachmentsRequest.Builder builder = ListVnicAttachmentsRequest.builder()
                    .compartmentId(compartmentId).instanceId(instanceOcid);
            if (page != null) builder.page(page);
            ListVnicAttachmentsResponse response = compute.listVnicAttachments(builder.build());
            for (VnicAttachment attachment : response.getItems()) {
                if (attachment.getLifecycleState() != VnicAttachment.LifecycleState.Attached) continue;
                if (Integer.valueOf(0).equals(attachment.getNicIndex())) return attachment;
                if (fallback == null) fallback = attachment;
            }
            page = response.getOpcNextPage();
        } while (page != null);
        return fallback;
    }

    private PrivateIp findPrimaryPrivateIp(VirtualNetworkClient network, String vnicId) {
        String page = null;
        PrivateIp fallback = null;
        do {
            ListPrivateIpsRequest.Builder builder = ListPrivateIpsRequest.builder().vnicId(vnicId);
            if (page != null) builder.page(page);
            ListPrivateIpsResponse response = network.listPrivateIps(builder.build());
            for (PrivateIp privateIp : response.getItems()) {
                if (privateIp.getIsPrimary()) return privateIp;
                if (fallback == null) fallback = privateIp;
            }
            page = response.getOpcNextPage();
        } while (page != null);
        return fallback;
    }

    private PublicIp getPublicIp(VirtualNetworkClient network, String address) {
        try {
            GetPublicIpByIpAddressResponse response = network.getPublicIpByIpAddress(
                    GetPublicIpByIpAddressRequest.builder().getPublicIpByIpAddressDetails(
                            com.oracle.bmc.core.model.GetPublicIpByIpAddressDetails.builder()
                                    .ipAddress(address).build()).build());
            return response.getPublicIp();
        } catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 404) return null;
            throw e;
        }
    }

    private void waitUntilDetached(VirtualNetworkClient network, String oldAddress) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DETACH_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (getPublicIp(network, oldAddress) == null) return;
            Thread.sleep(DETACH_POLL_MILLIS);
        }
        throw new IllegalStateException("OCI 旧公网 IP 解绑超时");
    }

    private String ocidSuffix(String ocid) {
        if (ocid == null || ocid.length() <= 8) return "unknown";
        return ocid.substring(ocid.length() - 8);
    }

    private String safeDisplayName(Instance instance) {
        String displayName = instance.getDisplayName();
        return displayName == null || displayName.trim().isEmpty() ? "instance" : displayName.trim();
    }

    private static final class OciClients implements AutoCloseable {
        private final IdentityClient identity;
        private final ComputeClient compute;
        private final VirtualNetworkClient network;

        private OciClients(IdentityClient identity, ComputeClient compute, VirtualNetworkClient network) {
            this.identity = identity;
            this.compute = compute;
            this.network = network;
        }

        @Override
        public void close() {
            identity.close();
            compute.close();
            network.close();
        }
    }
}
