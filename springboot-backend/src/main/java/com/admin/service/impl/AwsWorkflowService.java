package com.admin.service.impl;

import com.admin.common.dto.*;
import com.admin.common.lang.R;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.admin.service.AwsEc2Service;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executor;

/** Explicitly opted-in workflows. A failed or interrupted replacement is never silently retried. */
@Service
public class AwsWorkflowService {
    private static final Set<String> CONDITIONS = Set.of("vcpu_zero", "vcpu_exhausted", "spot_interrupted", "instance_missing", "traffic_limit");
    @Resource private AwsShareGroupMapper shares;
    @Resource private AwsFailoverConfigMapper configs;
    @Resource private AwsFailoverJobMapper jobs;
    @Resource private NodeMapper nodes;
    @Resource private NodeVpsUsageMapper usage;
    @Resource private AwsAccountMapper accounts;
    @Resource private AwsEc2InstanceTemplateMapper templates;
    @Resource private AwsAccountServiceImpl accountService;
    @Resource private AwsEc2ProviderService provider;
    @Resource @Lazy private AwsEc2Service ec2;
    @Resource private AwsOperationService operations;
    @Resource private AwsNodeBindingMapper bindings;
    @Resource(name = "awsOperationExecutor") private Executor executor;

    public List<Map<String, Object>> listShares() {
        return shares.selectList(new QueryWrapper<AwsShareGroup>().orderByDesc("id")).stream().map(group -> {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("id", group.getId()); view.put("name", group.getName()); view.put("expiresAt", group.getExpiresAt());
            view.put("permissions", group.getPermissions()); view.put("nodeIds", ids(group.getNodeIdsJson()));
            view.put("nodeCount", ids(group.getNodeIdsJson()).size()); view.put("accessToken", group.getAccessToken());
            return view;
        }).toList();
    }

    @Transactional
    public R saveShare(AwsShareGroupSaveDto dto) {
        if (dto == null || dto.getName() == null || dto.getName().isBlank() || dto.getName().length() > 255)
            return R.err("请输入分享组名称（最多255字）");
        if (!Set.of("read", "operate").contains(dto.getPermissions())) return R.err("分享权限只能为只读或启停操作");
        List<Long> selected = validateNodes(dto.getNodeIds());
        AwsShareGroup group = dto.getId() == null || dto.getId() == 0 ? new AwsShareGroup() : shares.selectById(dto.getId());
        if (group == null) return R.err("分享组不存在");
        group.setName(dto.getName().trim()); group.setPermissions(dto.getPermissions());
        group.setNodeIdsJson(JSON.toJSONString(selected)); group.setExpiresAt(dto.getExpiresAt());
        group.setUpdatedTime(System.currentTimeMillis());
        if (group.getAccessToken() == null) group.setAccessToken(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        if (group.getId() == null) {
            group.setCreatedTime(System.currentTimeMillis()); group.setStatus(0); shares.insert(group);
        } else {
            shares.updateById(group);
            // Null expiry is an explicit request to remove the expiry, not an omitted field.
            shares.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AwsShareGroup>()
                    .eq("id", group.getId()).set("expires_at", dto.getExpiresAt()));
        }
        return R.ok();
    }

    public Map<String, Object> sharedInstances(String token) {
        AwsShareGroup group = requireShare(token);
        List<Map<String, Object>> instances = new ArrayList<>();
        for (Long id : ids(group.getNodeIdsJson())) {
            Node node = nodes.selectById(id);
            if (node == null || !"AWS".equals(node.getCloudProvider())) continue;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("nodeId", id); view.put("name", node.getName()); view.put("instanceId", node.getAwsInstanceId());
            view.put("region", node.getAwsRegion()); view.put("state", "UNKNOWN");
            view.put("publicIpv4", node.getServerIpv4()); view.put("ipv6", node.getServerIpv6());
            view.put("gfwStatus", node.getWallMonitorExternalStatus()); view.put("lastProbeAt", node.getWallMonitorExternalLastCheckAt());
            instances.add(view);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", group.getName()); result.put("permissions", group.getPermissions());
        result.put("expiresAt", group.getExpiresAt()); result.put("instances", instances);
        return result;
    }

    public Map<String, Object> sharedAction(String token, Long nodeId, String action, String requestId) {
        AwsShareGroup group = requireShare(token);
        if (!"operate".equals(group.getPermissions()) || !ids(group.getNodeIdsJson()).contains(nodeId)
                || action == null || !Set.of("start", "stop", "reboot").contains(action))
            throw new IllegalArgumentException("分享权限不允许此操作");
        Node node = nodes.selectById(nodeId);
        if (node == null || !"AWS".equals(node.getCloudProvider())) throw new IllegalArgumentException("节点不可用");
        AwsEc2ActionDto dto = new AwsEc2ActionDto();
        dto.setAccountId(node.getAwsAccountId()); dto.setInstanceId(node.getAwsInstanceId());
        dto.setRegion(node.getAwsRegion()); dto.setAction(action);
        Map<String, Object> queued = operations.submit(requestId, dto.getAccountId(), dto.getRegion(), dto.getInstanceId(), action, () -> {
            AwsShareGroup current = requireShare(token);
            if (!"operate".equals(current.getPermissions()) || !ids(current.getNodeIdsJson()).contains(nodeId))
                throw new IllegalArgumentException("分享权限已撤销");
            return ec2.action(dto);
        });
        return Map.of("operationId", queued.get("operationId"), "status", queued.get("status"));
    }

    AwsShareGroup requireShare(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("分享链接无效或已到期");
        AwsShareGroup group = shares.selectOne(new QueryWrapper<AwsShareGroup>().eq("access_token", token));
        if (group == null || group.getExpiresAt() != null && group.getExpiresAt() <= System.currentTimeMillis())
            throw new IllegalArgumentException("分享链接无效或已到期");
        return group;
    }

    public Map<String, Object> configView() {
        AwsFailoverConfig c = configs.selectById(1L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", c != null && Integer.valueOf(1).equals(c.getEnabled()));
        result.put("schedulerActive", true); result.put("backupGroupId", c == null ? null : c.getBackupGroupId());
        result.put("maxInstances", c == null ? 1 : c.getMaxInstances()); result.put("cron", c == null ? "*/10 * * * *" : c.getCron());
        result.put("conditions", c == null ? List.of() : c.getConditions()); result.put("nodeIds", c == null ? List.of() : ids(c.getNodeIdsJson()));
        result.put("templateId", c == null ? null : c.getTemplateId()); result.put("accountSelection", c == null ? "ordered" : c.getAccountSelection());
        result.put("requiredVcpu", c == null ? 2 : c.getRequiredVcpu()); result.put("trafficLimitBytes", c == null ? null : c.getTrafficLimitBytes());
        result.put("telegramEnabled", c != null && Integer.valueOf(1).equals(c.getTelegramEnabled()));
        result.put("telegramConfigured", c != null && c.getTelegramBotTokenEncrypted() != null);
        result.put("telegramChatId", c == null ? "" : c.getTelegramChatId());
        return result;
    }

    @Transactional
    public R saveConfig(AwsFailoverConfigDto dto) {
        if (dto == null) return R.err("自动替补配置无效");
        String cron = dto.getCron() == null || dto.getCron().isBlank() ? "*/10 * * * *" : dto.getCron().trim();
        nextRun(cron, System.currentTimeMillis());
        List<String> conditions = dto.getConditions() == null ? List.of() : dto.getConditions().stream().distinct().toList();
        if (!CONDITIONS.containsAll(conditions)) return R.err("自动替补条件无效");
        List<Long> selected = validateNodes(dto.getNodeIds());
        boolean enabled = Boolean.TRUE.equals(dto.getEnabled());
        int maximum = dto.getMaxInstances() == null ? 1 : dto.getMaxInstances();
        if (maximum < 1 || maximum > 100) return R.err("最大替补实例数必须为1至100");
        int required = dto.getRequiredVcpu() == null ? 2 : dto.getRequiredVcpu();
        if (required < 1 || required > 1024) return R.err("所需 vCPU 无效");
        String selection = dto.getAccountSelection() == null ? "ordered" : dto.getAccountSelection();
        if (!Set.of("ordered", "random").contains(selection)) return R.err("账号选择顺序无效");
        if (enabled && (conditions.isEmpty() || selected.isEmpty() || dto.getTemplateId() == null
                || templates.selectById(dto.getTemplateId()) == null || dto.getBackupGroupId() == null
                || accounts.selectCount(new QueryWrapper<AwsAccount>().eq("group_id", dto.getBackupGroupId()).eq("status", 0)) == 0))
            return R.err("启用前必须选择节点、条件、可用创建模板和含备用账号的分组");
        if (conditions.contains("traffic_limit") && (dto.getTrafficLimitBytes() == null || dto.getTrafficLimitBytes() <= 0))
            return R.err("请设置大于0的流量上限");
        AwsFailoverConfig c = configs.selectById(1L);
        boolean insert = c == null;
        if (insert) { c = new AwsFailoverConfig(); c.setId(1L); }
        c.setEnabled(enabled ? 1 : 0); c.setBackupGroupId(dto.getBackupGroupId()); c.setMaxInstances(maximum);
        c.setCron(cron); c.setConditionsJson(JSON.toJSONString(conditions)); c.setNodeIdsJson(JSON.toJSONString(selected));
        c.setTemplateId(dto.getTemplateId()); c.setAccountSelection(selection); c.setRequiredVcpu(required);
        c.setTrafficLimitBytes(dto.getTrafficLimitBytes()); c.setNextCheckAt(nextRun(cron, System.currentTimeMillis()));
        c.setTelegramEnabled(Boolean.TRUE.equals(dto.getTelegramEnabled()) ? 1 : 0);
        if (dto.getTelegramBotToken() != null && !dto.getTelegramBotToken().isBlank()) {
            if (!dto.getTelegramBotToken().matches("[0-9]+:[A-Za-z0-9_-]{20,}")) return R.err("Telegram Token 格式无效");
            c.setTelegramBotTokenEncrypted(accountService.encryptValue(dto.getTelegramBotToken()));
        }
        if (dto.getTelegramChatId() != null) c.setTelegramChatId(dto.getTelegramChatId().trim());
        if (insert) configs.insert(c); else configs.updateById(c);
        return R.ok(configView());
    }

    public List<AwsFailoverJob> logs() {
        return jobs.selectList(new QueryWrapper<AwsFailoverJob>().orderByDesc("created_at").last("LIMIT 100"));
    }

    /**
     * Queues an explicit replacement for one AWS-backed node.  This is deliberately
     * separate from the scheduler: an administrator must identify the current
     * binding, and a retry with the same request id is returned by AwsOperationService
     * instead of creating another instance.
     */
    public Map<String, Object> submitManualReplacement(AwsManualFailoverDto dto) {
        Node old = resolveManualSource(dto);
        AwsFailoverConfig config = configs.selectById(1L);
        validateManualConfig(config);
        String requestedId = dto == null ? null : dto.getRequestId();
        final String requestId = requestedId == null || requestedId.isBlank() ? UUID.randomUUID().toString() : requestedId;
        dto.setRequestId(requestId);
        final Node sourceNode = old;
        final AwsFailoverConfig sourceConfig = config;
        final String manualSource = "manual:" + sourceNode.getId() + ":" + requestId;
        // A manual retry is explicit.  It may proceed after a previous UNKNOWN job,
        // but it is still serialized by AwsOperationService while the first request runs.
        return operations.submit(requestId, sourceNode.getAwsAccountId(), sourceNode.getAwsRegion(),
                sourceNode.getAwsInstanceId(), "failover", dto, () -> {
                    AwsFailoverJob job = replaceInternal(sourceConfig, sourceNode, manualSource,
                            "manual", false);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("failoverJobId", job == null ? null : job.getId());
                    result.put("status", job == null ? "FAILED" : job.getStatus());
                    result.put("message", job == null ? "替补失败，请检查配置、账号配额与权限" : job.getMessage());
                    result.put("oldInstanceRetained", true);
                    return result;
                });
    }

    private Node resolveManualSource(AwsManualFailoverDto dto) {
        boolean hasNode = dto != null && dto.getNodeId() != null;
        boolean hasLocator = dto != null && dto.getAccountId() != null
                && dto.getRegion() != null && !dto.getRegion().isBlank()
                && dto.getInstanceId() != null && !dto.getInstanceId().isBlank();
        if (!hasNode && !hasLocator) throw new IllegalArgumentException("请提供节点 ID，或完整的 AWS 账号、区域和实例 ID");
        Node node;
        if (hasNode) {
            node = nodes.selectById(dto.getNodeId());
        } else {
            node = nodes.selectOne(new QueryWrapper<Node>().eq("cloud_provider", "AWS")
                    .eq("aws_account_id", dto.getAccountId()).eq("aws_region", dto.getRegion().trim())
                    .eq("aws_instance_id", dto.getInstanceId().trim()));
        }
        if (node == null || !"AWS".equals(node.getCloudProvider()) || node.getAwsAccountId() == null
                || node.getAwsInstanceId() == null || node.getAwsRegion() == null)
            throw new IllegalArgumentException("未找到匹配的 AWS 节点绑定");
        if (hasNode && hasLocator && (!Objects.equals(node.getAwsAccountId(), dto.getAccountId())
                || !Objects.equals(node.getAwsRegion(), dto.getRegion().trim())
                || !Objects.equals(node.getAwsInstanceId(), dto.getInstanceId().trim())))
            throw new IllegalArgumentException("AWS 实例与节点绑定不一致");
        return node;
    }

    private void validateManualConfig(AwsFailoverConfig config) {
        if (config == null || config.getBackupGroupId() == null || config.getTemplateId() == null
                || templates.selectById(config.getTemplateId()) == null
                || accounts.selectCount(new QueryWrapper<AwsAccount>().eq("group_id", config.getBackupGroupId()).eq("status", 0)) == 0)
            throw new IllegalStateException("请先配置可用的替补模板和备用账号组");
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 30000)
    public void tick() {
        AwsFailoverConfig c = configs.selectById(1L);
        if (c == null || !Integer.valueOf(1).equals(c.getEnabled())) return;
        long now = System.currentTimeMillis();
        long lease = now + 20 * 60_000L;
        if (configs.claim(now, nextRun(c.getCron(), now), lease) != 1) return;
        try { executor.execute(() -> { try { runCycle(c); } finally { configs.release(lease); } }); }
        catch (RuntimeException rejected) { configs.release(lease); }
    }

    void runCycle(AwsFailoverConfig c) {
        long started = System.currentTimeMillis();
        // A crash can leave a cloud resource alive. Keep its source locked and count it toward the cap.
        jobs.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AwsFailoverJob>()
                .in("status", "PENDING", "CREATED").lt("created_at", System.currentTimeMillis() - 20 * 60_000L)
                .set("status", "UNKNOWN").set("message", "任务中断，请在 AWS 核对资源，未自动重复创建"));
        for (Long nodeId : ids(c.getNodeIdsJson())) {
            if (System.currentTimeMillis() - started > 5 * 60_000L) return;
            AwsFailoverConfig latest = configs.selectById(1L);
            if (!sameConfig(c, latest)) return;
            Node node = nodes.selectById(nodeId);
            if (node == null || !"AWS".equals(node.getCloudProvider())) continue;
            String source = nodeId + ":" + node.getAwsAccountId() + ":" + node.getAwsRegion() + ":" + node.getAwsInstanceId();
            if (hasBlockingReplacementJob(node)) continue;
            if (jobs.selectCount(new QueryWrapper<AwsFailoverJob>().in("status", "PENDING", "CREATED", "SUCCEEDED", "UNKNOWN")) >= c.getMaxInstances()) return;
            try {
                String reason = reason(c, node);
                if (reason != null) { replace(c, node, source, reason); return; }
            } catch (RuntimeException failedCheck) {
                // Permission, network and disabled-region errors are not proof that an instance is missing.
            }
        }
    }

    String reason(AwsFailoverConfig c, Node node) {
        AwsEc2ListDto filter = new AwsEc2ListDto(); filter.setAccountId(node.getAwsAccountId()); filter.setRegion(node.getAwsRegion());
        // Listing without an instance filter distinguishes a genuinely absent ID from access denial.
        Map<String, Object> instance = ec2.listInstances(filter).stream()
                .filter(item -> Objects.equals(item.get("instanceId"), node.getAwsInstanceId())).findFirst().orElse(null);
        List<String> conditions = c.getConditions();
        if (conditions.contains("instance_missing") && (instance == null || "terminated".equals(instance.get("state")))) return "instance_missing";
        if (conditions.contains("spot_interrupted") && instance != null && Boolean.TRUE.equals(instance.get("spotInterrupted"))) return "spot_interrupted";
        if (conditions.contains("vcpu_zero") || conditions.contains("vcpu_exhausted")) {
            Map<String, Object> quota = accountService.quota(node.getAwsAccountId(), node.getAwsRegion());
            if (conditions.contains("vcpu_zero") && number(quota.get("quota")) == 0) return "vcpu_zero";
            if (conditions.contains("vcpu_exhausted") && number(quota.get("available")) < c.getRequiredVcpu()) return "vcpu_exhausted";
        }
        if (conditions.contains("traffic_limit") && node.getCurrentUsageId() != null) {
            NodeVpsUsage current = usage.selectById(node.getCurrentUsageId());
            AwsFailoverJob previous = jobs.selectOne(new QueryWrapper<AwsFailoverJob>().eq("node_id", node.getId())
                    .eq("status", "SUCCEEDED").orderByDesc("finished_at").last("LIMIT 1"));
            if (current != null && (previous == null || current.getStartedAt() != null && current.getStartedAt() > previous.getFinishedAt())
                    && number(current.getUploadBytes()) + number(current.getDownloadBytes()) >= c.getTrafficLimitBytes()) return "traffic_limit";
        }
        return null;
    }

    void replace(AwsFailoverConfig c, Node old, String source, String reason) {
        replaceInternal(c, old, source, reason, true);
    }

    private AwsFailoverJob replaceInternal(AwsFailoverConfig c, Node old, String source, String reason,
                                           boolean requireActiveConfig) {
        AwsFailoverJob job = new AwsFailoverJob(); job.setId(UUID.randomUUID().toString()); job.setSourceKey(source);
        job.setNodeId(old.getId()); job.setOldAccountId(old.getAwsAccountId()); job.setOldInstanceId(old.getAwsInstanceId());
        job.setOldRegion(old.getAwsRegion()); job.setReason(reason); job.setStatus("PENDING"); job.setCreatedAt(System.currentTimeMillis());
        try { jobs.insert(job); } catch (DuplicateKeyException claimed) { return null; }
        boolean submitted = false;
        try {
            List<AwsAccount> candidates = new ArrayList<>(accounts.selectList(new QueryWrapper<AwsAccount>()
                    .eq("group_id", c.getBackupGroupId()).eq("status", 0).ne("id", old.getAwsAccountId()).orderByAsc("id")));
            if ("random".equals(c.getAccountSelection())) Collections.shuffle(candidates);
            AwsEc2InstanceTemplate template = templates.selectById(c.getTemplateId());
            if (template == null) throw new IllegalStateException("替补模板已删除");
            String region = template.getRegion() == null || template.getRegion().isBlank() ? old.getAwsRegion() : template.getRegion();
            int requiredVcpu = c.getRequiredVcpu() == null ? 2 : Math.max(1, c.getRequiredVcpu());
            AwsAccount target = null;
            for (AwsAccount candidate : candidates) {
                try {
                    if (number(accountService.quota(candidate.getId(), region).get("available")) >= requiredVcpu) { target = candidate; break; }
                } catch (RuntimeException unavailable) { /* Skip unusable account; never classify it as zero quota. */ }
            }
            if (target == null) throw new IllegalStateException("没有可用备用账号");
            job.setNewAccountId(target.getId()); job.setNewRegion(region); jobs.updateById(job);
            if (requireActiveConfig && !sameConfig(c, configs.selectById(1L))) throw new IllegalStateException("配置已修改");
            AwsEc2CreateDto create = new AwsEc2CreateDto(); create.setTemplateId(c.getTemplateId());
            create.setAccountId(target.getId()); create.setRegion(region); create.setName(old.getName() + "-replacement");
            create.setClientToken(job.getId());
            submitted = true;
            Map<String, Object> created = ec2.createInstance(create);
            job.setNewInstanceId(String.valueOf(created.get("instanceId"))); job.setStatus("CREATED"); jobs.updateById(job);
            accountService.prepareAccount(target);
            Map<String, Object> ready = provider.awaitReady(target, accountService.decryptAccessSecret(target),
                    accountService.decryptSessionToken(target), region, job.getNewInstanceId());
            String ipv4 = Objects.toString(ready.get("publicIpv4"), "");
            String ipv6 = Objects.toString(ready.get("ipv6"), "");
            String ip = ipv4.isBlank() ? ipv6 : ipv4;
            AwsFailoverConfig latest = configs.selectById(1L);
            if (requireActiveConfig && (!sameConfig(c, latest) || !ids(latest.getNodeIdsJson()).contains(old.getId())))
                throw new IllegalStateException("配置已停用，请手动核对新实例");
            if (nodes.replaceAwsBinding(old.getId(), old.getAwsAccountId(), old.getAwsInstanceId(), old.getAwsRegion(),
                    target.getId(), job.getNewInstanceId(), region, ip, ipv4, ipv6) != 1) throw new IllegalStateException("节点绑定已变化");
            // Node columns are authoritative; keep the legacy compatibility record consistent.
            AwsNodeBinding binding = bindings.selectById(old.getId());
            if (binding != null) { binding.setAccountId(target.getId()); binding.setInstanceId(job.getNewInstanceId());
                binding.setRegion(region); binding.setUpdatedTime(System.currentTimeMillis()); bindings.updateById(binding); }
            job.setStatus("SUCCEEDED"); job.setMessage("替补完成；旧实例保留，新实例需安装节点程序后接入");
            WebSocketServer.broadcastMessage(JSON.toJSONString(Map.of("id", old.getId(), "type", "cloudBinding", "data",
                    Map.of("cloudProvider", "AWS", "awsAccountId", target.getId(), "awsInstanceId", job.getNewInstanceId(), "awsRegion", region, "serverIp", ip))));
        } catch (Exception failure) {
            job.setStatus(submitted ? "UNKNOWN" : "FAILED");
            job.setMessage(submitted ? "创建或绑定未确认；请核对新实例，未自动删除资源或重复创建" : "替补失败，请检查模板、账号配额与权限");
        } finally {
            job.setFinishedAt(System.currentTimeMillis()); jobs.updateById(job); notifyTelegram(c, job);
        }
        return job;
    }

    private void notifyTelegram(AwsFailoverConfig c, AwsFailoverJob job) {
        if (!Integer.valueOf(1).equals(c.getTelegramEnabled()) || c.getTelegramBotTokenEncrypted() == null || c.getTelegramChatId() == null) return;
        try {
            String token = accountService.decryptValue(c.getTelegramBotTokenEncrypted());
            if (!token.matches("[0-9]+:[A-Za-z0-9_-]{20,}")) return;
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of("chat_id", c.getTelegramChatId(),
                            "text", "AWS 节点 " + job.getNodeId() + " 替补结果：" + job.getStatus() + "。" + job.getMessage())))).build();
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) { /* Notification failure never changes replacement outcome; tokens are never logged. */ }
    }

    List<Long> validateNodes(List<Long> selected) {
        List<Long> result = selected == null ? List.of() : selected.stream().filter(Objects::nonNull).distinct().toList();
        if (result.size() > 100) throw new IllegalArgumentException("最多选择100个节点");
        for (Long id : result) {
            Node node = nodes.selectById(id);
            if (node == null || !"AWS".equals(node.getCloudProvider())) throw new IllegalArgumentException("只能选择已绑定 AWS 的节点");
        }
        return result;
    }

    static long nextRun(String cron, long now) {
        String value = cron == null ? "" : cron.trim();
        if (value.split("\\s+").length == 5) value = "0 " + value;
        try {
            var next = CronExpression.parse(value).next(Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")));
            if (next == null) throw new IllegalArgumentException();
            return Math.max(now + 60_000L, next.toInstant().toEpochMilli());
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("CRON 格式无效（5位分钟制或6位秒制）"); }
    }

    static List<Long> ids(String json) { return json == null || json.isBlank() ? List.of() : JSON.parseArray(json, Long.class); }
    private boolean hasBlockingReplacementJob(Node node) {
        return jobs.selectCount(new QueryWrapper<AwsFailoverJob>()
                .eq("node_id", node.getId()).eq("old_account_id", node.getAwsAccountId())
                .eq("old_instance_id", node.getAwsInstanceId()).eq("old_region", node.getAwsRegion())
                .in("status", "PENDING", "CREATED", "SUCCEEDED", "UNKNOWN")) > 0;
    }
    private boolean sameConfig(AwsFailoverConfig expected, AwsFailoverConfig actual) {
        if (actual == null || !Integer.valueOf(1).equals(actual.getEnabled())) return false;
        return Objects.equals(expected.getTemplateId(), actual.getTemplateId())
                && Objects.equals(expected.getBackupGroupId(), actual.getBackupGroupId())
                && Objects.equals(expected.getNodeIdsJson(), actual.getNodeIdsJson())
                && Objects.equals(expected.getConditionsJson(), actual.getConditionsJson())
                && Objects.equals(expected.getMaxInstances(), actual.getMaxInstances())
                && Objects.equals(expected.getRequiredVcpu(), actual.getRequiredVcpu())
                && Objects.equals(expected.getTrafficLimitBytes(), actual.getTrafficLimitBytes())
                && Objects.equals(expected.getAccountSelection(), actual.getAccountSelection());
    }
    static double number(Object value) {
        if (!(value instanceof Number)) throw new IllegalStateException("缺少可信的数值数据");
        return ((Number) value).doubleValue();
    }
}
