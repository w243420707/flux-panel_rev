package com.admin.service.impl;

import com.admin.common.dto.AwsEc2ActionDto;
import com.admin.common.dto.AwsEc2ChangeIpDto;
import com.admin.common.dto.AwsEc2CreateDto;
import com.admin.entity.AwsAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AwsEc2ProviderServiceTest {
    private AwsEc2ProviderService provider;
    private Ec2Client client;
    private AwsAccount account;

    @BeforeEach
    void setup() {
        client = mock(Ec2Client.class);
        AwsClientFactory factory = mock(AwsClientFactory.class);
        account = new AwsAccount(); account.setId(7L); account.setName("test"); account.setRegion("us-east-1");
        when(factory.ec2(eq(account), anyString(), anyString(), eq("us-east-1"))).thenReturn(client);
        provider = spy(new AwsEc2ProviderService()); ReflectionTestUtils.setField(provider, "clientFactory", factory);
        doNothing().when(provider).pause(anyLong());
        when(client.describeAddresses(any(DescribeAddressesRequest.class))).thenReturn(DescribeAddressesResponse.builder().build());
    }

    @Test
    void paginationIncludesResourcesAndDoesNotMistakeDynamicIpForElastic() {
        when(client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(instances(instance("running", "203.0.113.1")).toBuilder().nextToken("page2").build())
                .thenReturn(instances(instance("stopped", null).toBuilder().instanceId("i-second").build()));
        when(client.describeInstanceTypes(any(DescribeInstanceTypesRequest.class)))
                .thenReturn(DescribeInstanceTypesResponse.builder().instanceTypes(type()).build());
        List<Map<String, Object>> list = provider.listInstances(account, "secret", "token", "us-east-1", null);
        assertEquals(2, list.size()); assertEquals(2, list.get(0).get("vcpu")); assertEquals(1.0, list.get(0).get("memoryGiB"));
        assertEquals("Up to 5 Gigabit", list.get(0).get("bandwidth")); assertEquals(false, list.get(0).get("elasticIp"));
        ArgumentCaptor<DescribeInstancesRequest> request = ArgumentCaptor.forClass(DescribeInstancesRequest.class);
        verify(client, times(2)).describeInstances(request.capture()); assertEquals("page2", request.getAllValues().get(1).nextToken());
    }

    @Test
    void createsCorrectAmiRootMappingAndStableIdempotentUserData() {
        stubCreate();
        AwsEc2CreateDto dto = createDto(); dto.setRootVolumeSize(40); dto.setEnableIpv6(true); dto.setSpot(true);
        dto.setPassword("difficult:pass'word"); dto.setUserData("#!/bin/bash\necho ready"); dto.setDisablePublicIp(true);
        provider.createInstance(account, "secret", "token", "us-east-1", dto);
        provider.createInstance(account, "secret", "token", "us-east-1", dto);
        ArgumentCaptor<RunInstancesRequest> requests = ArgumentCaptor.forClass(RunInstancesRequest.class);
        verify(client, times(2)).runInstances(requests.capture());
        RunInstancesRequest request = requests.getAllValues().get(0);
        assertEquals("request-1", request.clientToken()); assertEquals("/dev/sda1", request.blockDeviceMappings().get(0).deviceName());
        assertEquals(40, request.blockDeviceMappings().get(0).ebs().volumeSize());
        assertEquals("subnet-1", request.networkInterfaces().get(0).subnetId());
        assertEquals(List.of("sg-1"), request.networkInterfaces().get(0).groups());
        assertEquals(1, request.networkInterfaces().get(0).ipv6AddressCount());
        assertFalse(request.networkInterfaces().get(0).associatePublicIpAddress());
        assertEquals(MarketType.SPOT, request.instanceMarketOptions().marketType());
        assertEquals(request.userData(), requests.getAllValues().get(1).userData());
        String decoded = new String(Base64.getDecoder().decode(request.userData()), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("multipart/mixed")); assertTrue(decoded.contains("echo ready"));
        assertTrue(decoded.contains("password: \"difficult:pass'word\""));
    }

    @Test
    void rejectsArchitectureAndDiskMismatchBeforeCreating() {
        stubCreate(); AwsEc2CreateDto dto = createDto(); dto.setArchitecture("arm64");
        assertThrows(IllegalArgumentException.class, () -> provider.createInstance(account, "secret", "token", "us-east-1", dto));
        dto.setArchitecture("x86_64"); dto.setRootVolumeSize(2);
        assertThrows(IllegalArgumentException.class, () -> provider.createInstance(account, "secret", "token", "us-east-1", dto));
        verify(client, never()).runInstances(any(RunInstancesRequest.class));
    }

    @Test
    void neverDetachesOrReleasesUserElasticIpOrExplicitlyPreservedAddress() {
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(instances(instance("running", "203.0.113.1")));
        Address userAddress = oldAddress(false);
        when(client.describeAddresses(any(DescribeAddressesRequest.class))).thenReturn(DescribeAddressesResponse.builder().addresses(userAddress).build());
        for (String strategy : List.of("AUTO", "EIP", "DYNAMIC")) {
            AwsEc2ChangeIpDto dto = ipDto(strategy);
            assertThrows(IllegalArgumentException.class, () -> provider.changeIp(account, "secret", "token", "us-east-1", dto));
        }
        when(client.describeAddresses(any(DescribeAddressesRequest.class))).thenReturn(DescribeAddressesResponse.builder().addresses(oldAddress(true).toBuilder()
                .tags(Tag.builder().key(AwsEc2ProviderService.MANAGED_TAG).value("true").build(),
                        Tag.builder().key("flux-panel:preserve").value("true").build()).build()).build());
        assertThrows(IllegalArgumentException.class, () -> provider.changeIp(account, "secret", "token", "us-east-1", ipDto("AUTO")));
        verify(client, never()).disassociateAddress(any(DisassociateAddressRequest.class));
        verify(client, never()).releaseAddress(any(ReleaseAddressRequest.class));
        verify(client, never()).stopInstances(any(StopInstancesRequest.class));
    }

    @Test
    void dynamicIpWaitsForStoppedThenRunningWithNewAddress() {
        when(client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(instances(instance("running", "203.0.113.1")))
                .thenReturn(instances(instance("stopped", null)))
                .thenReturn(instances(instance("running", "203.0.113.2")));
        Map<String, Object> result = provider.changeIp(account, "secret", "token", "us-east-1", ipDto("DYNAMIC"));
        assertEquals("SUCCEEDED", result.get("status")); assertEquals("203.0.113.2", result.get("publicIp"));
        var order = inOrder(client); order.verify(client).describeInstances(any(DescribeInstancesRequest.class));
        order.verify(client).describeAddresses(any(DescribeAddressesRequest.class)); order.verify(client).stopInstances(any(StopInstancesRequest.class));
        order.verify(client).describeInstances(any(DescribeInstancesRequest.class)); order.verify(client).startInstances(any(StartInstancesRequest.class));
        order.verify(client).describeInstances(any(DescribeInstancesRequest.class));
    }

    @Test
    void cidrAttemptsAreBoundedAndDoNotDetachOldAddressUntilMatch() {
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(instances(instance("running", "203.0.113.1")));
        when(client.describeAddresses(any(DescribeAddressesRequest.class))).thenReturn(DescribeAddressesResponse.builder().addresses(oldAddress(true)).build());
        when(client.allocateAddress(any(AllocateAddressRequest.class)))
                .thenReturn(AllocateAddressResponse.builder().allocationId("new1").publicIp("198.51.100.1").build())
                .thenReturn(AllocateAddressResponse.builder().allocationId("new2").publicIp("198.51.100.2").build());
        AwsEc2ChangeIpDto dto = ipDto("EIP"); dto.setCidr("203.0.113.0/24"); dto.setMaxAttempts(2);
        assertThrows(IllegalStateException.class, () -> provider.changeIp(account, "secret", "token", "us-east-1", dto));
        verify(client, times(2)).allocateAddress(any(AllocateAddressRequest.class));
        verify(client, times(2)).releaseAddress(any(ReleaseAddressRequest.class));
        verify(client, never()).disassociateAddress(any(DisassociateAddressRequest.class));
    }

    @Test
    void elasticRotationTagsNewAllocationAndReleasesOldOnlyAfterConfirmedAssociation() {
        when(client.describeInstances(any(DescribeInstancesRequest.class)))
                .thenReturn(instances(instance("running", "203.0.113.1")))
                .thenReturn(instances(instance("running", "203.0.113.2")));
        when(client.describeAddresses(any(DescribeAddressesRequest.class))).thenReturn(DescribeAddressesResponse.builder().addresses(oldAddress(true)).build());
        when(client.allocateAddress(any(AllocateAddressRequest.class))).thenReturn(AllocateAddressResponse.builder()
                .allocationId("new").publicIp("203.0.113.2").build());
        AwsEc2ChangeIpDto dto = ipDto("EIP"); dto.setReleaseOld(true);
        assertEquals("SUCCEEDED", provider.changeIp(account, "secret", "token", "us-east-1", dto).get("status"));
        ArgumentCaptor<AllocateAddressRequest> allocate = ArgumentCaptor.forClass(AllocateAddressRequest.class);
        verify(client).allocateAddress(allocate.capture()); assertTrue(allocate.getValue().tagSpecifications().get(0).tags().stream()
                .anyMatch(t -> AwsEc2ProviderService.MANAGED_TAG.equals(t.key()) && "true".equals(t.value())));
        var order = inOrder(client); order.verify(client).disassociateAddress(any(DisassociateAddressRequest.class));
        order.verify(client).associateAddress(any(AssociateAddressRequest.class));
        order.verify(client).describeInstances(any(DescribeInstancesRequest.class));
        order.verify(client).releaseAddress(argThat((ReleaseAddressRequest r) -> "old".equals(r.allocationId())));
    }

    @Test
    void associationFailureRestoresOldAndNeverLeaksSensitiveAwsError() {
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(instances(instance("running", "203.0.113.1")));
        when(client.describeAddresses(any(DescribeAddressesRequest.class))).thenAnswer(invocation -> {
            DescribeAddressesRequest request = invocation.getArgument(0);
            return DescribeAddressesResponse.builder().addresses(request.allocationIds().isEmpty() ? oldAddress(true)
                    : Address.builder().allocationId("new").publicIp("203.0.113.2").build()).build();
        });
        when(client.allocateAddress(any(AllocateAddressRequest.class))).thenReturn(AllocateAddressResponse.builder()
                .allocationId("new").publicIp("203.0.113.2").build());
        when(client.associateAddress(any(AssociateAddressRequest.class))).thenThrow(SdkClientException.create("secret=do-not-show"))
                .thenReturn(AssociateAddressResponse.builder().associationId("restored").build());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.changeIp(account, "secret", "token", "us-east-1", ipDto("EIP")));
        assertFalse(error.getMessage().contains("do-not-show")); assertNull(error.getCause());
        verify(client).associateAddress(argThat((AssociateAddressRequest r) -> "old".equals(r.allocationId())));
        verify(client).releaseAddress(argThat((ReleaseAddressRequest r) -> "new".equals(r.allocationId())));
        verify(client, never()).releaseAddress(argThat((ReleaseAddressRequest r) -> "old".equals(r.allocationId())));
    }

    @Test
    void uncertainAssociationDoesNotReleasePossiblyActiveAddress() {
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(instances(instance("running", "203.0.113.1")));
        when(client.describeAddresses(any(DescribeAddressesRequest.class)))
                .thenReturn(DescribeAddressesResponse.builder().addresses(oldAddress(true)).build())
                .thenReturn(DescribeAddressesResponse.builder().addresses(Address.builder().allocationId("new")
                        .associationId("new-assoc").networkInterfaceId("eni-1").build()).build());
        when(client.allocateAddress(any(AllocateAddressRequest.class))).thenReturn(AllocateAddressResponse.builder().allocationId("new").publicIp("203.0.113.2").build());
        when(client.associateAddress(any(AssociateAddressRequest.class))).thenThrow(SdkClientException.create("timeout"));
        assertThrows(IllegalStateException.class, () -> provider.changeIp(account, "secret", "token", "us-east-1", ipDto("EIP")));
        verify(client, never()).releaseAddress(any(ReleaseAddressRequest.class));
        verify(client, times(1)).associateAddress(any(AssociateAddressRequest.class));
    }

    @Test
    void reinstallRetainsOldRootVolumeAndUsesClientToken() {
        stubCreate(); when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(instances(instance("running", "203.0.113.1")));
        when(client.createReplaceRootVolumeTask(any(CreateReplaceRootVolumeTaskRequest.class))).thenReturn(CreateReplaceRootVolumeTaskResponse.builder()
                .replaceRootVolumeTask(ReplaceRootVolumeTask.builder().replaceRootVolumeTaskId("replace-1").build()).build());
        AwsEc2ActionDto dto = actionDto("reinstall"); dto.setImageId("ami-1"); dto.setClientToken("reinstall-1");
        Map<String, Object> result = provider.action(account, "secret", "token", "us-east-1", dto);
        assertEquals("ACCEPTED", result.get("status")); assertEquals("replace-1", result.get("taskId"));
        ArgumentCaptor<CreateReplaceRootVolumeTaskRequest> request = ArgumentCaptor.forClass(CreateReplaceRootVolumeTaskRequest.class);
        verify(client).createReplaceRootVolumeTask(request.capture()); assertFalse(request.getValue().deleteReplacedRootVolume());
        assertEquals("reinstall-1", request.getValue().clientToken());
    }

    @Test
    void ipv6AssignmentRequiresEnabledSubnetAndReleaseMustBelongToInstance() {
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(instances(instance("running", "203.0.113.1")));
        when(client.describeSubnets(any(DescribeSubnetsRequest.class))).thenReturn(DescribeSubnetsResponse.builder()
                .subnets(Subnet.builder().subnetId("subnet-1").state(SubnetState.AVAILABLE).build()).build());
        assertThrows(IllegalArgumentException.class, () -> provider.action(account, "secret", "token", "us-east-1", actionDto("ipv6-assign")));
        verify(client, never()).assignIpv6Addresses(any(AssignIpv6AddressesRequest.class));
        AwsEc2ActionDto dto = actionDto("ipv6-unassign"); dto.setIpv6Addresses(List.of("2001:db8::123"));
        assertThrows(IllegalArgumentException.class, () -> provider.action(account, "secret", "token", "us-east-1", dto));
        verify(client, never()).unassignIpv6Addresses(any(UnassignIpv6AddressesRequest.class));
    }

    @Test
    void modifyRejectsShrinkAndRunningUserDataBeforeAnyWrites() {
        when(client.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(instances(instance("running", "203.0.113.1")));
        AwsEc2ActionDto dto = actionDto("modify"); dto.setName("changed"); dto.setUserData("");
        assertThrows(IllegalArgumentException.class, () -> provider.action(account, "secret", "token", "us-east-1", dto));
        dto.setUserData(null); dto.setRootVolumeSize(1);
        when(client.describeVolumes(any(DescribeVolumesRequest.class))).thenReturn(DescribeVolumesResponse.builder()
                .volumes(Volume.builder().volumeId("vol-root").volumeType(VolumeType.GP3).size(20).build()).build());
        assertThrows(IllegalArgumentException.class, () -> provider.action(account, "secret", "token", "us-east-1", dto));
        verify(client, never()).createTags(any(CreateTagsRequest.class)); verify(client, never()).modifyVolume(any(ModifyVolumeRequest.class));
    }

    @Test
    void manualHealthCheckNeverClaimsGfwReachability() {
        when(client.describeInstanceStatus(any(DescribeInstanceStatusRequest.class))).thenReturn(DescribeInstanceStatusResponse.builder().build());
        assertEquals("UNKNOWN", provider.gfwCheck(account, "secret", "token", "us-east-1", "i-1").get("gfwStatus"));
        verify(client, never()).stopInstances(any(StopInstancesRequest.class));
    }

    @Test
    void malformedCidrAndOutOfRangeAttemptsAreRejectedWithoutCloudWrites() {
        AwsEc2ChangeIpDto dto = ipDto("EIP"); dto.setCidr("example.com/24");
        assertThrows(IllegalArgumentException.class, () -> provider.changeIp(account, "secret", "token", "us-east-1", dto));
        dto.setCidr(null); dto.setMaxAttempts(11);
        assertThrows(IllegalArgumentException.class, () -> provider.changeIp(account, "secret", "token", "us-east-1", dto));
        verify(client, never()).allocateAddress(any(AllocateAddressRequest.class));
    }

    private void stubCreate() {
        when(client.describeImages(any(DescribeImagesRequest.class))).thenReturn(DescribeImagesResponse.builder().images(Image.builder()
                .imageId("ami-1").state(ImageState.AVAILABLE).architecture(ArchitectureValues.X86_64).rootDeviceType(DeviceType.EBS)
                .rootDeviceName("/dev/sda1").blockDeviceMappings(BlockDeviceMapping.builder().deviceName("/dev/sda1")
                        .ebs(EbsBlockDevice.builder().volumeSize(8).build()).build()).build()).build());
        when(client.describeInstanceTypes(any(DescribeInstanceTypesRequest.class))).thenReturn(DescribeInstanceTypesResponse.builder().instanceTypes(type()).build());
        when(client.describeSubnets(any(DescribeSubnetsRequest.class))).thenReturn(DescribeSubnetsResponse.builder().subnets(Subnet.builder()
                .subnetId("subnet-1").availabilityZone("us-east-1a").state(SubnetState.AVAILABLE)
                .ipv6CidrBlockAssociationSet(SubnetIpv6CidrBlockAssociation.builder().ipv6CidrBlock("2001:db8::/64")
                        .ipv6CidrBlockState(SubnetCidrBlockState.builder().state(SubnetCidrBlockStateCode.ASSOCIATED).build()).build()).build()).build());
        when(client.runInstances(any(RunInstancesRequest.class))).thenReturn(RunInstancesResponse.builder().instances(instance("pending", null)).build());
    }

    private InstanceTypeInfo type() {
        return InstanceTypeInfo.builder().instanceType(InstanceType.T3_MICRO)
                .processorInfo(ProcessorInfo.builder().supportedArchitectures(ArchitectureType.X86_64).build())
                .vCpuInfo(VCpuInfo.builder().defaultVCpus(2).build()).memoryInfo(MemoryInfo.builder().sizeInMiB(1024L).build())
                .networkInfo(NetworkInfo.builder().networkPerformance("Up to 5 Gigabit").build()).build();
    }

    private Instance instance(String state, String ip) {
        return Instance.builder().instanceId("i-1").instanceType(InstanceType.T3_MICRO).architecture(ArchitectureValues.X86_64)
                .rootDeviceType(DeviceType.EBS).rootDeviceName("/dev/sda1").publicIpAddress(ip).privateIpAddress("10.0.0.1")
                .state(InstanceState.builder().name(state).build()).networkInterfaces(InstanceNetworkInterface.builder().networkInterfaceId("eni-1")
                        .subnetId("subnet-1").attachment(InstanceNetworkInterfaceAttachment.builder().deviceIndex(0).build()).build())
                .blockDeviceMappings(InstanceBlockDeviceMapping.builder().deviceName("/dev/sda1")
                        .ebs(EbsInstanceBlockDevice.builder().volumeId("vol-root").build()).build()).build();
    }

    private Address oldAddress(boolean managed) {
        Address.Builder builder = Address.builder().instanceId("i-1").allocationId("old").associationId("old-assoc")
                .privateIpAddress("10.0.0.1").publicIp("203.0.113.1");
        if (managed) builder.tags(Tag.builder().key(AwsEc2ProviderService.MANAGED_TAG).value("true").build()); return builder.build();
    }

    private DescribeInstancesResponse instances(Instance instance) { return DescribeInstancesResponse.builder().reservations(Reservation.builder().instances(instance).build()).build(); }
    private AwsEc2CreateDto createDto() {
        AwsEc2CreateDto dto = new AwsEc2CreateDto(); dto.setImageId("ami-1"); dto.setInstanceType("t3.micro"); dto.setSubnetId("subnet-1");
        dto.setSecurityGroupIds(List.of("sg-1")); dto.setClientToken("request-1"); return dto;
    }
    private AwsEc2ActionDto actionDto(String action) { AwsEc2ActionDto dto = new AwsEc2ActionDto(); dto.setInstanceId("i-1"); dto.setAction(action); return dto; }
    private AwsEc2ChangeIpDto ipDto(String strategy) { AwsEc2ChangeIpDto dto = new AwsEc2ChangeIpDto(); dto.setInstanceId("i-1"); dto.setStrategy(strategy); return dto; }
}
