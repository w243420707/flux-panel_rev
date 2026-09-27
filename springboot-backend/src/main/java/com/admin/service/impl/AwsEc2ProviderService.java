package com.admin.service.impl;

import com.admin.common.dto.AwsEc2ActionDto;
import com.admin.common.dto.AwsEc2ChangeIpDto;
import com.admin.common.dto.AwsEc2CreateDto;
import com.admin.entity.AwsAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Authorization, credentials, scheduling and audit state live in the service layer. */
@Slf4j
@Service
public class AwsEc2ProviderService {
    static final String MANAGED_TAG = "flux-panel:managed";
    static final String INSTANCE_TAG = "flux-panel:instance-id";
    private static final long STATE_TIMEOUT_MILLIS = 180_000L;
    private static final long POLL_MILLIS = 2_000L;
    @Resource
    private AwsClientFactory clientFactory;

    public List<Map<String, Object>> listInstances(AwsAccount account, String secret, String token,
                                                   String region, String instanceId) {
        try (Ec2Client client = clientFactory.ec2(account, secret, token, region)) {
            List<Instance> instances = new ArrayList<>();
            String next = null;
            Set<String> seen = new HashSet<>();
            do {
                DescribeInstancesRequest.Builder request = DescribeInstancesRequest.builder().nextToken(next);
                if (StringUtils.hasText(instanceId)) request.instanceIds(instanceId.trim()); else request.maxResults(100);
                DescribeInstancesResponse response = client.describeInstances(request.build());
                response.reservations().forEach(item -> instances.addAll(item.instances()));
                next = response.nextToken();
                if (StringUtils.hasText(next) && !seen.add(next)) throw new IllegalStateException("AWS 实例分页未推进，请稍后重试");
            } while (StringUtils.hasText(next));
            Map<String, InstanceTypeInfo> types = describeTypes(client, instances);
            Set<String> elasticIps = client.describeAddresses(DescribeAddressesRequest.builder().build()).addresses().stream()
                    .map(Address::publicIp).filter(StringUtils::hasText).collect(Collectors.toSet());
            return instances.stream().map(instance -> instanceView(instance, account, region,
                    types.get(instance.instanceTypeAsString()), elasticIps.contains(instance.publicIpAddress())))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private Map<String, InstanceTypeInfo> describeTypes(Ec2Client client, List<Instance> instances) {
        List<String> names = instances.stream().map(Instance::instanceTypeAsString).filter(StringUtils::hasText).distinct().toList();
        Map<String, InstanceTypeInfo> result = new HashMap<>();
        for (int offset = 0; offset < names.size(); offset += 100) {
            List<String> batch = names.subList(offset, Math.min(offset + 100, names.size()));
            String next = null;
            Set<String> seen = new HashSet<>();
            do {
                DescribeInstanceTypesResponse response = client.describeInstanceTypes(DescribeInstanceTypesRequest.builder()
                        .instanceTypesWithStrings(batch).nextToken(next).build());
                for (InstanceTypeInfo info : response.instanceTypes()) result.put(info.instanceTypeAsString(), info);
                next = response.nextToken();
                if (StringUtils.hasText(next) && !seen.add(next)) throw new IllegalStateException("AWS 型号分页未推进");
            } while (StringUtils.hasText(next));
        }
        return result;
    }

    /** Used by failover before changing the persisted node binding. */
    public Map<String, Object> awaitReady(AwsAccount account, String secret, String token, String region, String instanceId) {
        try (Ec2Client client = clientFactory.ec2(account, secret, token, region)) {
            Instance running = waitForInstance(client, instanceId, item -> "running".equals(state(item))
                    && (StringUtils.hasText(item.publicIpAddress()) || item.networkInterfaces().stream()
                    .anyMatch(n -> !n.ipv6Addresses().isEmpty())), STATE_TIMEOUT_MILLIS,
                    "新实例未在限定时间内运行并获得公网地址；未切换节点绑定");
            return instanceView(running, account, region, describeTypes(client, List.of(running)).get(running.instanceTypeAsString()),
                    currentAddress(client, running) != null);
        }
    }

    /** Compatibility entrypoint; new callers should pass the complete DTO. */
    public Map<String, Object> createInstance(AwsAccount account, String secret, String token, String region,
                                               String imageId, String instanceType, String keyName, String subnetId,
                                               List<String> securityGroups, String userData, String name,
                                               Integer rootSize, Boolean spot, String availabilityZone) {
        AwsEc2CreateDto dto = new AwsEc2CreateDto();
        dto.setImageId(imageId); dto.setInstanceType(instanceType); dto.setKeyName(keyName); dto.setSubnetId(subnetId);
        dto.setSecurityGroupIds(securityGroups); dto.setUserData(userData); dto.setName(name);
        dto.setRootVolumeSize(rootSize); dto.setSpot(spot); dto.setAvailabilityZone(availabilityZone);
        return createInstance(account, secret, token, region, dto);
    }

    public Map<String, Object> createInstance(AwsAccount account, String secret, String token, String region,
                                               AwsEc2CreateDto dto) {
        if (dto == null || !StringUtils.hasText(dto.getImageId()) || !StringUtils.hasText(dto.getInstanceType()))
            throw new IllegalArgumentException("创建 EC2 必须提供镜像 ID 和实例类型");
        if (Boolean.TRUE.equals(dto.getStaticIp()) && Boolean.TRUE.equals(dto.getDisablePublicIp()))
            throw new IllegalArgumentException("静态公网 IP 与禁用公网 IP 不能同时选择");
        if (StringUtils.hasText(dto.getCidr()) && !Boolean.TRUE.equals(dto.getStaticIp()))
            throw new IllegalArgumentException("指定 CIDR 需要启用静态 Elastic IP");
        Cidr cidr = Cidr.parse(dto.getCidr());
        String requestToken = clientToken(dto.getClientToken());
        try (Ec2Client client = clientFactory.ec2(account, secret, token, region)) {
            Image image = findImage(client, dto.getImageId());
            validateArchitecture(client, image, dto.getArchitecture(), dto.getInstanceType());
            Subnet subnet = resolveSubnet(client, dto.getSubnetId(), dto.getAvailabilityZone());
            if (Boolean.TRUE.equals(dto.getEnableIpv6())) requireIpv6Subnet(subnet);
            InstanceNetworkInterfaceSpecification.Builder network = InstanceNetworkInterfaceSpecification.builder()
                    .deviceIndex(0).subnetId(subnet.subnetId()).deleteOnTermination(true)
                    .associatePublicIpAddress(!Boolean.TRUE.equals(dto.getDisablePublicIp()) && !Boolean.TRUE.equals(dto.getStaticIp()));
            if (dto.getSecurityGroupIds() != null && !dto.getSecurityGroupIds().isEmpty())
                network.groups(dto.getSecurityGroupIds().stream().map(String::trim).filter(StringUtils::hasText).distinct().toList());
            if (Boolean.TRUE.equals(dto.getEnableIpv6())) network.ipv6AddressCount(1);
            List<Tag> tags = new ArrayList<>(List.of(Tag.builder().key(MANAGED_TAG).value("true").build()));
            if (StringUtils.hasText(dto.getName())) tags.add(Tag.builder().key("Name").value(dto.getName().trim()).build());
            RunInstancesRequest.Builder request = RunInstancesRequest.builder().imageId(image.imageId())
                    .instanceType(dto.getInstanceType().trim()).minCount(1).maxCount(1).clientToken(requestToken)
                    .networkInterfaces(network.build()).tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE).tags(tags).build());
            if (StringUtils.hasText(dto.getKeyName())) request.keyName(dto.getKeyName().trim());
            if (StringUtils.hasText(dto.getAvailabilityZone())) request.placement(Placement.builder()
                    .availabilityZone(dto.getAvailabilityZone().trim()).build());
            Integer size = dto.getRootVolumeSize() == null ? dto.getDiskGiB() : dto.getRootVolumeSize();
            if (size != null) {
                if (image.rootDeviceType() != DeviceType.EBS || !StringUtils.hasText(image.rootDeviceName()))
                    throw new IllegalArgumentException("仅 EBS 根盘镜像支持指定根磁盘大小");
                Integer minimum = image.blockDeviceMappings().stream().filter(m -> image.rootDeviceName().equals(m.deviceName()))
                        .filter(m -> m.ebs() != null).map(m -> m.ebs().volumeSize()).filter(Objects::nonNull).findFirst().orElse(1);
                if (size < minimum || size > 16384) throw new IllegalArgumentException("根磁盘大小不能小于镜像要求或超过 16384 GiB");
                request.blockDeviceMappings(BlockDeviceMapping.builder().deviceName(image.rootDeviceName())
                        .ebs(EbsBlockDevice.builder().volumeSize(size).volumeType(VolumeType.GP3).deleteOnTermination(true).build()).build());
            }
            String userData = buildUserData(dto.getUserData(), dto.getPassword(), image, requestToken);
            if (StringUtils.hasText(userData)) request.userData(Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8)));
            if (Boolean.TRUE.equals(dto.getSpot())) request.instanceMarketOptions(InstanceMarketOptionsRequest.builder()
                    .marketType(MarketType.SPOT).spotOptions(SpotMarketOptions.builder().spotInstanceType(SpotInstanceType.ONE_TIME)
                            .instanceInterruptionBehavior(InstanceInterruptionBehavior.TERMINATE).build()).build());
            Instance created = client.runInstances(request.build()).instances().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("AWS 未返回创建的 EC2 实例"));
            Map<String, Object> result = instanceView(created, account, region, null, false);
            result.put("clientToken", requestToken); result.put("status", "ACCEPTED"); result.put("message", "AWS 已接受创建请求");
            if (Boolean.TRUE.equals(dto.getStaticIp())) {
                Instance running = waitForInstance(client, created.instanceId(), item -> "running".equals(state(item)), STATE_TIMEOUT_MILLIS,
                        "实例尚未就绪；创建已提交，请刷新实例后再绑定静态 IP");
                Address current = currentAddress(client, running);
                if (current != null) {
                    requirePanelManaged(current);
                    if (cidr == null || cidr.contains(current.publicIp())) {
                        result.put("publicIp", current.publicIp()); result.put("publicIpv4", current.publicIp());
                        result.put("elasticIp", true); result.put("status", "SUCCEEDED"); return result;
                    }
                    throw new IllegalStateException("同一创建请求已绑定静态 IP；如需更换请使用换 IP 操作");
                }
                Map<String, Object> address = rotateElastic(client, running, null, cidr, 5, false);
                result.put("publicIp", address.get("publicIp")); result.put("publicIpv4", address.get("publicIp"));
                result.put("elasticIp", true); result.put("status", "SUCCEEDED");
            }
            return result;
        }
    }

    public Map<String, Object> action(AwsAccount account, String secret, String token, String region, String instanceId,
                                      String action, String name, String userData, String instanceType,
                                      Integer size, String volumeType, Integer iops, Integer throughput) {
        AwsEc2ActionDto dto = new AwsEc2ActionDto();
        dto.setInstanceId(instanceId); dto.setAction(action); dto.setName(name); dto.setUserData(userData);
        dto.setInstanceType(instanceType); dto.setRootVolumeSize(size); dto.setRootVolumeType(volumeType);
        dto.setRootVolumeIops(iops); dto.setRootVolumeThroughput(throughput);
        return action(account, secret, token, region, dto);
    }

    public Map<String, Object> action(AwsAccount account, String secret, String token, String region, AwsEc2ActionDto dto) {
        if (dto == null || !StringUtils.hasText(dto.getInstanceId())) throw new IllegalArgumentException("EC2 实例 ID 不能为空");
        String id = dto.getInstanceId().trim();
        String action = dto.getAction() == null ? "" : dto.getAction().trim().toLowerCase(Locale.ROOT);
        try (Ec2Client client = clientFactory.ec2(account, secret, token, region)) {
            Instance instance = findInstance(client, id);
            Map<String, Object> result = result(id, action, "ACCEPTED", "AWS 已接受操作请求，请刷新实例状态");
            switch (action) {
                case "start" -> client.startInstances(StartInstancesRequest.builder().instanceIds(id).build());
                case "stop" -> client.stopInstances(StopInstancesRequest.builder().instanceIds(id).build());
                case "reboot" -> client.rebootInstances(RebootInstancesRequest.builder().instanceIds(id).build());
                case "terminate" -> client.terminateInstances(TerminateInstancesRequest.builder().instanceIds(id).build());
                case "modify", "update" -> modify(client, instance, dto);
                case "reinstall" -> {
                    if (!"running".equals(state(instance)) || instance.rootDeviceType() != DeviceType.EBS)
                        throw new IllegalArgumentException("重装需要运行中的 EBS 根盘实例；旧根盘将保留供恢复");
                    Image image = findImage(client, dto.getImageId());
                    if (!Objects.equals(image.architectureAsString(), instance.architectureAsString()))
                        throw new IllegalArgumentException("新镜像与当前实例架构不匹配");
                    var response = client.createReplaceRootVolumeTask(CreateReplaceRootVolumeTaskRequest.builder()
                            .instanceId(id).imageId(image.imageId()).clientToken(clientToken(dto.getClientToken()))
                            .deleteReplacedRootVolume(false).build());
                    if (response.replaceRootVolumeTask() == null) throw new IllegalStateException("AWS 未返回重装任务");
                    result.put("taskId", response.replaceRootVolumeTask().replaceRootVolumeTaskId());
                    result.put("message", "重装任务已提交，旧根盘保留；请在 AWS 确认完成后按需清理旧盘");
                }
                case "ipv6-enable", "ipv6-assign" -> {
                    InstanceNetworkInterface nic = primaryNetworkInterface(instance);
                    requireIpv6Subnet(resolveSubnet(client, nic.subnetId(), null));
                    if ("ipv6-enable".equals(action) && !nic.ipv6Addresses().isEmpty())
                        result.put("ipv6Addresses", nic.ipv6Addresses().stream().map(InstanceIpv6Address::ipv6Address).toList());
                    else result.put("ipv6Addresses", client.assignIpv6Addresses(AssignIpv6AddressesRequest.builder()
                            .networkInterfaceId(nic.networkInterfaceId()).ipv6AddressCount(1).build()).assignedIpv6Addresses());
                    result.put("status", "SUCCEEDED");
                }
                case "ipv6-unassign" -> {
                    InstanceNetworkInterface nic = primaryNetworkInterface(instance);
                    List<String> current = nic.ipv6Addresses().stream().map(InstanceIpv6Address::ipv6Address).toList();
                    List<String> selected = dto.getIpv6Addresses() == null || dto.getIpv6Addresses().isEmpty()
                            ? current : dto.getIpv6Addresses().stream().distinct().toList();
                    if (!current.containsAll(selected)) throw new IllegalArgumentException("只能释放当前实例主网卡已绑定的 IPv6");
                    if (!selected.isEmpty()) client.unassignIpv6Addresses(UnassignIpv6AddressesRequest.builder()
                            .networkInterfaceId(nic.networkInterfaceId()).ipv6Addresses(selected).build());
                    result.put("status", "SUCCEEDED");
                }
                default -> throw new IllegalArgumentException("不支持的 EC2 操作");
            }
            return result;
        }
    }

    public Map<String, Object> changeIp(AwsAccount account, String secret, String token, String region,
                                        String instanceId, String strategy, Boolean releaseOld) {
        AwsEc2ChangeIpDto dto = new AwsEc2ChangeIpDto(); dto.setInstanceId(instanceId);
        dto.setStrategy(strategy); dto.setReleaseOld(releaseOld);
        return changeIp(account, secret, token, region, dto);
    }

    public Map<String, Object> changeIp(AwsAccount account, String secret, String token, String region, AwsEc2ChangeIpDto dto) {
        if (dto == null || !StringUtils.hasText(dto.getInstanceId())) throw new IllegalArgumentException("EC2 实例 ID 不能为空");
        String mode = StringUtils.hasText(dto.getStrategy()) ? dto.getStrategy().trim().toUpperCase(Locale.ROOT) : "AUTO";
        if (!Set.of("AUTO", "EIP", "DYNAMIC").contains(mode)) throw new IllegalArgumentException("换 IP 策略无效");
        Cidr cidr = Cidr.parse(dto.getCidr());
        int attempts = dto.getMaxAttempts() == null ? 5 : dto.getMaxAttempts();
        if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("CIDR 尝试次数必须为 1–10");
        try (Ec2Client client = clientFactory.ec2(account, secret, token, region)) {
            Instance instance = findInstance(client, dto.getInstanceId().trim());
            Address current = currentAddress(client, instance);
            if (current != null) requirePanelManaged(current);
            if ("EIP".equals(mode) || ("AUTO".equals(mode) && (current != null || cidr != null)))
                return rotateElastic(client, instance, current, cidr, attempts, Boolean.TRUE.equals(dto.getReleaseOld()));
            if (cidr != null) throw new IllegalArgumentException("动态公网 IP 不支持指定 CIDR，请选择 Elastic IP");
            if (current != null) throw new IllegalArgumentException("当前实例绑定了 Elastic IP，请选择 EIP 策略");
            if (!"running".equals(state(instance))) throw new IllegalArgumentException("动态换 IP 只支持运行中的实例");
            if (instance.rootDeviceType() != DeviceType.EBS) throw new IllegalArgumentException("动态换 IP 需要 EBS 根盘实例");
            if (!StringUtils.hasText(instance.publicIpAddress())) throw new IllegalArgumentException("实例未启用动态公网 IPv4");
            if (instance.instanceLifecycle() == InstanceLifecycleType.SPOT)
                throw new IllegalArgumentException("Spot 实例的停止限制不同，请使用面板管理的 Elastic IP 换 IP");
            client.stopInstances(StopInstancesRequest.builder().instanceIds(instance.instanceId()).build());
            waitForInstance(client, instance.instanceId(), item -> "stopped".equals(state(item)), STATE_TIMEOUT_MILLIS,
                    "实例停止超时，未继续启动；请检查 AWS 实例状态");
            client.startInstances(StartInstancesRequest.builder().instanceIds(instance.instanceId()).build());
            Instance running = waitForInstance(client, instance.instanceId(), item -> "running".equals(state(item))
                    && StringUtils.hasText(item.publicIpAddress()) && !item.publicIpAddress().equals(instance.publicIpAddress()),
                    STATE_TIMEOUT_MILLIS, "实例启动或新公网 IP 等待超时，请检查 AWS 实例状态");
            Map<String, Object> result = result(instance.instanceId(), "DYNAMIC", "SUCCEEDED", "实例已重新启动并获得新的动态公网 IP");
            result.put("strategy", "DYNAMIC"); result.put("publicIp", running.publicIpAddress()); return result;
        }
    }

    private Map<String, Object> rotateElastic(Ec2Client client, Instance instance, Address current, Cidr cidr,
                                               int attempts, boolean releaseOld) {
        if (current != null) requirePanelManaged(current);
        InstanceNetworkInterface nic = primaryNetworkInterface(instance);
        AllocateAddressResponse allocated = null;
        for (int attempt = 0; attempt < (cidr == null ? 1 : attempts); attempt++) {
            AllocateAddressResponse candidate = client.allocateAddress(AllocateAddressRequest.builder().domain(DomainType.VPC)
                    .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.ELASTIC_IP).tags(
                            Tag.builder().key(MANAGED_TAG).value("true").build(),
                            Tag.builder().key(INSTANCE_TAG).value(instance.instanceId()).build()).build()).build());
            if (cidr == null || cidr.contains(candidate.publicIp())) { allocated = candidate; break; }
            releaseAllocated(client, candidate.allocationId());
        }
        if (allocated == null) throw new IllegalStateException("限定次数内未获得目标 CIDR 的 IP；原 IP 未更改");
        boolean associated = false;
        boolean oldDetached = false;
        try {
            if (current != null && StringUtils.hasText(current.associationId())) {
                client.disassociateAddress(DisassociateAddressRequest.builder().associationId(current.associationId()).build());
                oldDetached = true;
            }
            client.associateAddress(AssociateAddressRequest.builder().allocationId(allocated.allocationId())
                    .networkInterfaceId(nic.networkInterfaceId()).privateIpAddress(instance.privateIpAddress())
                    .allowReassociation(false).build());
            associated = true;
            String newIp = allocated.publicIp();
            waitForInstance(client, instance.instanceId(), item -> newIp.equals(item.publicIpAddress()), 30_000L,
                    "新 Elastic IP 已绑定，但实例状态尚未收敛，请刷新确认");
            Map<String, Object> result = result(instance.instanceId(), "EIP", "SUCCEEDED", "新的 Elastic IP 已绑定");
            result.put("strategy", "EIP"); result.put("publicIp", allocated.publicIp()); result.put("allocationId", allocated.allocationId());
            if (releaseOld && current != null && StringUtils.hasText(current.allocationId())) {
                try { releaseAllocated(client, current.allocationId()); }
                catch (RuntimeException error) {
                    result.put("message", "新 IP 已绑定，旧面板 IP 保留待清理"); result.put("oldAllocationRetained", true);
                    log.warn("AWS old managed EIP cleanup failed; resource retained");
                }
            }
            return result;
        } catch (RuntimeException error) {
            if (!associated) {
                // An SDK timeout does not prove that AWS rejected the association.
                // Retain both addresses unless a read establishes the new one is unbound.
                Address observed;
                try {
                    observed = client.describeAddresses(DescribeAddressesRequest.builder()
                            .allocationIds(allocated.allocationId()).build()).addresses().stream().findFirst().orElse(null);
                } catch (RuntimeException inspectionError) {
                    throw new IllegalStateException("新 IP 绑定结果暂时无法确认；两个 IP 均已保留，请检查 AWS 绑定");
                }
                if (observed != null && (StringUtils.hasText(observed.associationId())
                        || StringUtils.hasText(observed.networkInterfaceId())))
                    throw new IllegalStateException("新 IP 已存在绑定，未自动回滚或释放；请刷新 AWS 实例状态确认");
                boolean restored = !oldDetached;
                if (oldDetached) {
                    try {
                        client.associateAddress(AssociateAddressRequest.builder().allocationId(current.allocationId())
                                .networkInterfaceId(nic.networkInterfaceId()).privateIpAddress(instance.privateIpAddress())
                                .allowReassociation(false).build());
                        restored = true;
                    } catch (RuntimeException restoreError) { log.warn("AWS EIP rollback requires manual inspection"); }
                }
                try { if (observed != null) releaseAllocated(client, allocated.allocationId()); }
                catch (RuntimeException releaseError) { log.warn("AWS unused managed EIP cleanup requires manual inspection"); }
                throw new IllegalStateException(restored ? "新 IP 绑定失败；原 IP 已保留或恢复" : "新 IP 绑定失败且原 IP 自动恢复未完成，请在 AWS 检查绑定");
            }
            throw error;
        }
    }

    public Map<String, Object> gfwCheck(AwsAccount account, String secret, String token, String region, String id) {
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("EC2 实例 ID 不能为空");
        try (Ec2Client client = clientFactory.ec2(account, secret, token, region)) {
            var response = client.describeInstanceStatus(DescribeInstanceStatusRequest.builder().instanceIds(id).includeAllInstances(true).build());
            Map<String, Object> result = new LinkedHashMap<>(); result.put("instanceId", id); result.put("status", "UNKNOWN");
            if (!response.instanceStatuses().isEmpty()) {
                InstanceStatus status = response.instanceStatuses().get(0);
                result.put("status", status.instanceState() == null ? "UNKNOWN" : status.instanceState().nameAsString());
                result.put("systemStatus", status.systemStatus() == null ? null : status.systemStatus().statusAsString());
                result.put("instanceStatus", status.instanceStatus() == null ? null : status.instanceStatus().statusAsString());
            }
            result.put("gfwStatus", "UNKNOWN");
            result.put("message", "仅查询 AWS 健康状态，不代表大陆可达性；被墙状态以 Android APK 报告为准"); return result;
        }
    }

    private void modify(Ec2Client client, Instance instance, AwsEc2ActionDto dto) {
        boolean changeType = StringUtils.hasText(dto.getInstanceType()) && !dto.getInstanceType().trim().equals(instance.instanceTypeAsString());
        if ((changeType || dto.getUserData() != null) && !"stopped".equals(state(instance)))
            throw new IllegalArgumentException("修改型号或开机脚本前请先停止实例");
        if (changeType) {
            var types = client.describeInstanceTypes(DescribeInstanceTypesRequest.builder().instanceTypesWithStrings(dto.getInstanceType().trim()).build());
            if (types.instanceTypes().isEmpty() || types.instanceTypes().get(0).processorInfo() == null
                    || !types.instanceTypes().get(0).processorInfo().supportedArchitecturesAsStrings().contains(instance.architectureAsString()))
                throw new IllegalArgumentException("新实例型号不支持当前架构");
        }
        ModifyVolumeRequest volumeRequest = volumeModification(client, instance, dto);
        if (dto.getUserData() != null && dto.getUserData().getBytes(StandardCharsets.UTF_8).length > 16384)
            throw new IllegalArgumentException("开机脚本不得超过 16 KiB");
        if (dto.getName() != null) client.createTags(CreateTagsRequest.builder().resources(instance.instanceId())
                .tags(Tag.builder().key("Name").value(dto.getName().trim()).build()).build());
        if (dto.getUserData() != null) client.modifyInstanceAttribute(ModifyInstanceAttributeRequest.builder()
                .instanceId(instance.instanceId()).userData(BlobAttributeValue.builder().value(SdkBytes.fromUtf8String(dto.getUserData())).build()).build());
        if (changeType) client.modifyInstanceAttribute(ModifyInstanceAttributeRequest.builder().instanceId(instance.instanceId())
                .instanceType(AttributeValue.builder().value(dto.getInstanceType().trim()).build()).build());
        if (volumeRequest != null) client.modifyVolume(volumeRequest);
    }

    private ModifyVolumeRequest volumeModification(Ec2Client client, Instance instance, AwsEc2ActionDto dto) {
        if (dto.getRootVolumeSize() == null && !StringUtils.hasText(dto.getRootVolumeType())
                && dto.getRootVolumeIops() == null && dto.getRootVolumeThroughput() == null) return null;
        String volumeId = instance.blockDeviceMappings().stream().filter(m -> Objects.equals(instance.rootDeviceName(), m.deviceName()) && m.ebs() != null)
                .map(m -> m.ebs().volumeId()).filter(StringUtils::hasText).findFirst().orElseThrow(() -> new IllegalArgumentException("找不到 EC2 根卷"));
        Volume current = client.describeVolumes(DescribeVolumesRequest.builder().volumeIds(volumeId).build()).volumes()
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("EC2 根卷不存在"));
        if (dto.getRootVolumeSize() != null && (dto.getRootVolumeSize() < current.size() || dto.getRootVolumeSize() > 16384))
            throw new IllegalArgumentException("根卷只支持扩容，且不能超过 16384 GiB");
        String type = StringUtils.hasText(dto.getRootVolumeType()) ? dto.getRootVolumeType().trim() : current.volumeTypeAsString();
        if (!Set.of("gp2", "gp3", "io1", "io2", "standard").contains(type)) throw new IllegalArgumentException("根卷类型无效");
        if (dto.getRootVolumeIops() != null && (dto.getRootVolumeIops() < 100 || !Set.of("gp3", "io1", "io2").contains(type)))
            throw new IllegalArgumentException("该磁盘类型或 IOPS 值不支持配置");
        if (dto.getRootVolumeThroughput() != null && (!"gp3".equals(type) || dto.getRootVolumeThroughput() < 125 || dto.getRootVolumeThroughput() > 1000))
            throw new IllegalArgumentException("吞吐量仅支持 gp3，范围 125–1000 MiB/s");
        ModifyVolumeRequest.Builder request = ModifyVolumeRequest.builder().volumeId(volumeId);
        if (dto.getRootVolumeSize() != null) request.size(dto.getRootVolumeSize());
        if (StringUtils.hasText(dto.getRootVolumeType())) request.volumeType(type);
        if (dto.getRootVolumeIops() != null) request.iops(dto.getRootVolumeIops());
        if (dto.getRootVolumeThroughput() != null) request.throughput(dto.getRootVolumeThroughput()); return request.build();
    }

    private Image findImage(Ec2Client client, String imageId) {
        if (!StringUtils.hasText(imageId)) throw new IllegalArgumentException("镜像 ID 不能为空");
        Image image = client.describeImages(DescribeImagesRequest.builder().imageIds(imageId.trim()).build()).images()
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("目标区域中找不到该镜像"));
        if (image.state() != ImageState.AVAILABLE) throw new IllegalArgumentException("镜像当前不可用"); return image;
    }

    private void validateArchitecture(Ec2Client client, Image image, String architecture, String type) {
        if (StringUtils.hasText(architecture) && !architecture.trim().equals(image.architectureAsString()))
            throw new IllegalArgumentException("所选架构与镜像不匹配");
        InstanceTypeInfo info = client.describeInstanceTypes(DescribeInstanceTypesRequest.builder().instanceTypesWithStrings(type.trim()).build())
                .instanceTypes().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("实例型号不存在"));
        if (info.processorInfo() == null || !info.processorInfo().supportedArchitecturesAsStrings().contains(image.architectureAsString()))
            throw new IllegalArgumentException("实例型号与镜像架构不匹配");
    }

    private Subnet resolveSubnet(Ec2Client client, String subnetId, String zone) {
        DescribeSubnetsRequest.Builder request = DescribeSubnetsRequest.builder();
        if (StringUtils.hasText(subnetId)) request.subnetIds(subnetId.trim());
        else {
            List<Filter> filters = new ArrayList<>(List.of(Filter.builder().name("default-for-az").values("true").build()));
            if (StringUtils.hasText(zone)) filters.add(Filter.builder().name("availability-zone").values(zone.trim()).build());
            request.filters(filters);
        }
        Subnet subnet = client.describeSubnets(request.build()).subnets().stream().filter(s -> s.state() == SubnetState.AVAILABLE)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("没有可用子网，请指定该区域的子网 ID"));
        if (StringUtils.hasText(zone) && !zone.trim().equals(subnet.availabilityZone())) throw new IllegalArgumentException("子网与可用区不匹配");
        return subnet;
    }

    private void requireIpv6Subnet(Subnet subnet) {
        if (subnet.ipv6CidrBlockAssociationSet().stream().noneMatch(a -> a.ipv6CidrBlockState() != null
                && a.ipv6CidrBlockState().state() == SubnetCidrBlockStateCode.ASSOCIATED))
            throw new IllegalArgumentException("该子网尚未配置 IPv6 CIDR；请先在 AWS 配置 IPv6 子网和路由");
    }

    private InstanceNetworkInterface primaryNetworkInterface(Instance instance) {
        return instance.networkInterfaces().stream().filter(n -> n.attachment() != null && Integer.valueOf(0).equals(n.attachment().deviceIndex()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到 EC2 主网卡"));
    }

    private Address currentAddress(Ec2Client client, Instance instance) {
        return client.describeAddresses(DescribeAddressesRequest.builder().build()).addresses().stream()
                .filter(a -> StringUtils.hasText(instance.publicIpAddress()) && instance.publicIpAddress().equals(a.publicIp())
                        || instance.instanceId().equals(a.instanceId()) && Objects.equals(instance.privateIpAddress(), a.privateIpAddress()))
                .findFirst().orElse(null);
    }

    private void requirePanelManaged(Address address) {
        if (address.tags().stream().noneMatch(t -> MANAGED_TAG.equals(t.key()) && "true".equalsIgnoreCase(t.value())))
            throw new IllegalArgumentException("当前 Elastic IP 不是面板创建的，已保护，不会解绑或释放");
        if (address.tags().stream().anyMatch(t -> "flux-panel:preserve".equals(t.key()) && "true".equalsIgnoreCase(t.value())))
            throw new IllegalArgumentException("当前 Elastic IP 已标记保留，不会解绑或释放");
    }

    private void releaseAllocated(Ec2Client client, String id) {
        client.releaseAddress(ReleaseAddressRequest.builder().allocationId(id).build());
    }

    private Instance findInstance(Ec2Client client, String id) {
        return client.describeInstances(DescribeInstancesRequest.builder().instanceIds(id).build()).reservations().stream()
                .flatMap(r -> r.instances().stream()).filter(i -> id.equals(i.instanceId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("EC2 实例不存在"));
    }

    Instance waitForInstance(Ec2Client client, String id, Predicate<Instance> completed, long timeoutMillis, String error) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        do {
            Instance current = findInstance(client, id);
            if (completed.test(current)) return current;
            if (Set.of("terminated", "shutting-down").contains(state(current))) throw new IllegalStateException("实例正在终止，操作无法完成");
            pause(POLL_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(error);
    }

    void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("AWS 操作已中断"); }
    }

    private String state(Instance instance) { return instance.state() == null ? "unknown" : instance.state().nameAsString(); }

    private String clientToken(String value) {
        String token = StringUtils.hasText(value) ? value.trim() : UUID.randomUUID().toString();
        if (!token.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("幂等请求标识必须是 1–64 位字母、数字、下划线或短横线");
        return token;
    }

    private String buildUserData(String script, String password, Image image, String requestToken) {
        String result = script == null ? "" : script;
        if (StringUtils.hasText(password)) {
            if (image.platform() == PlatformValues.WINDOWS) throw new IllegalArgumentException("密码初始化目前仅支持带 cloud-init 的 Linux 镜像");
            if (password.length() < 12 || password.length() > 128 || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0)
                throw new IllegalArgumentException("初始化密码须为 12–128 位且不能包含换行");
            String quoted = com.alibaba.fastjson2.JSON.toJSONString(password);
            String cloudConfig = "#cloud-config\nssh_pwauth: true\ndisable_root: false\nchpasswd:\n  expire: false\n  users:\n    - name: root\n      password: " + quoted + "\n      type: text\n";
            if (!StringUtils.hasText(result)) result = cloudConfig;
            else {
                String boundary = "flux-panel-" + requestToken;
                String type = result.startsWith("#cloud-config") ? "text/cloud-config" : "text/x-shellscript";
                if (!result.startsWith("#!") && !result.startsWith("#cloud-config")) result = "#!/bin/bash\n" + result;
                result = "MIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"" + boundary + "\"\n\n--" + boundary
                        + "\nContent-Type: text/cloud-config; charset=\"utf-8\"\n\n" + cloudConfig + "\n--" + boundary
                        + "\nContent-Type: " + type + "; charset=\"utf-8\"\n\n" + result + "\n--" + boundary + "--\n";
            }
        }
        if (result.getBytes(StandardCharsets.UTF_8).length > 16384) throw new IllegalArgumentException("开机脚本及密码配置合计不得超过 16 KiB");
        return result;
    }

    private Map<String, Object> result(String id, String action, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("instanceId", id); result.put("action", action);
        result.put("status", status); result.put("message", message); return result;
    }

    private Map<String, Object> instanceView(Instance instance, AwsAccount account, String region, InstanceTypeInfo type, boolean elastic) {
        Map<String, Object> item = new LinkedHashMap<>(); item.put("id", instance.instanceId()); item.put("instanceId", instance.instanceId());
        item.put("name", instance.tags().stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(""));
        item.put("state", state(instance)); item.put("status", state(instance)); item.put("accountId", account.getId());
        item.put("accountName", account.getName()); item.put("region", StringUtils.hasText(region) ? region : account.getRegion());
        item.put("availabilityZone", instance.placement() == null ? null : instance.placement().availabilityZone());
        item.put("publicIpv4", instance.publicIpAddress()); item.put("publicIp", instance.publicIpAddress());
        item.put("privateIpv4", instance.privateIpAddress()); item.put("instanceType", instance.instanceTypeAsString());
        List<String> ipv6 = instance.networkInterfaces().stream().flatMap(n -> n.ipv6Addresses().stream())
                .map(InstanceIpv6Address::ipv6Address).filter(StringUtils::hasText).toList();
        item.put("ipv6", ipv6.isEmpty() ? null : ipv6.get(0)); item.put("ipv6Addresses", ipv6);
        item.put("vcpu", type == null || type.vCpuInfo() == null ? null : type.vCpuInfo().defaultVCpus());
        item.put("memoryGiB", type == null || type.memoryInfo() == null || type.memoryInfo().sizeInMiB() == null ? null : type.memoryInfo().sizeInMiB() / 1024.0);
        item.put("bandwidth", type == null || type.networkInfo() == null ? null : type.networkInfo().networkPerformance());
        item.put("createdAt", instance.launchTime() == null ? null : instance.launchTime().toString());
        item.put("elasticIp", elastic); item.put("imageId", instance.imageId()); item.put("architecture", instance.architectureAsString());
        item.put("subnetId", instance.subnetId()); item.put("spot", instance.instanceLifecycle() == InstanceLifecycleType.SPOT);
        item.put("instanceLifecycle", instance.instanceLifecycleAsString());
        String reason = instance.stateReason() == null ? null : instance.stateReason().code();
        item.put("stateReasonCode", reason);
        // The arbitrary AWS message is deliberately not persisted or exposed.
        item.put("spotInterrupted", instance.instanceLifecycle() == InstanceLifecycleType.SPOT && reason != null
                && Set.of("Server.SpotInstanceTermination", "Server.SpotInstanceShutdown", "Server.InsufficientInstanceCapacity").contains(reason));
        return item;
    }

    /** IPv4 literal parsing only: never DNS-resolve user-supplied CIDRs. */
    private record Cidr(long network, long mask) {
        static Cidr parse(String value) {
            if (!StringUtils.hasText(value)) return null;
            String[] parts = value.trim().split("/", -1);
            try {
                if (parts.length != 2 || !parts[1].matches("[0-9]{1,2}")) throw new IllegalArgumentException();
                int bits = Integer.parseInt(parts[1]); if (bits < 0 || bits > 32) throw new IllegalArgumentException();
                long mask = bits == 0 ? 0 : (0xffffffffL << (32 - bits)) & 0xffffffffL;
                return new Cidr(ip(parts[0]) & mask, mask);
            } catch (RuntimeException error) { throw new IllegalArgumentException("CIDR 必须是有效的 IPv4 网段，如 203.0.113.0/24"); }
        }
        boolean contains(String address) { return (ip(address) & mask) == network; }
        static long ip(String value) {
            if (value == null) throw new IllegalArgumentException();
            String[] octets = value.split("\\.", -1); if (octets.length != 4) throw new IllegalArgumentException();
            long result = 0;
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}")) throw new IllegalArgumentException();
                int number = Integer.parseInt(octet); if (number > 255) throw new IllegalArgumentException(); result = (result << 8) | number;
            }
            return result;
        }
    }
}
