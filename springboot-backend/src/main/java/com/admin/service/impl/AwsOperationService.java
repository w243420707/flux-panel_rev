package com.admin.service.impl;

import com.admin.entity.AwsOperation;
import com.admin.mapper.AwsOperationMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

@Service
public class AwsOperationService {
    @Resource private AwsOperationMapper mapper;
    @Resource(name = "awsOperationExecutor") private Executor executor;

    public Map<String, Object> submit(String requestId, Long accountId, String region, String instanceId,
                                      String action, Callable<Map<String, Object>> task) {
        return submit(requestId, accountId, region, instanceId, action, null, task);
    }

    public Map<String, Object> submit(String requestId, Long accountId, String region, String instanceId,
                                      String action, Object payload, Callable<Map<String, Object>> task) {
        String id = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        if (!id.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("操作请求 ID 格式无效");
        String hash = requestHash(payload);
        AwsOperation existing = mapper.selectById(id);
        if (existing != null) {
            if (!java.util.Objects.equals(existing.getAccountId(), accountId)
                    || !java.util.Objects.equals(existing.getRegion(), region)
                    || !java.util.Objects.equals(existing.getInstanceId(), instanceId)
                    || !java.util.Objects.equals(existing.getAction(), action)
                    || !java.util.Objects.equals(existing.getRequestHash(), hash)) {
                throw new IllegalArgumentException("请求 ID 已用于其他操作");
            }
            return view(existing);
        }
        AwsOperation operation = new AwsOperation();
        operation.setId(id); operation.setAccountId(accountId); operation.setRegion(region);
        operation.setInstanceId(instanceId); operation.setAction(action); operation.setStatus("PENDING");
        operation.setRequestHash(hash);
        operation.setCreatedAt(System.currentTimeMillis());
        operation.setActiveTarget(accountId + ":" + region + ":" + (instanceId == null ? "create:" + id : instanceId));
        try { mapper.insert(operation); }
        catch (DuplicateKeyException duplicate) {
            existing = mapper.selectById(id);
            if (existing != null) return submit(id, accountId, region, instanceId, action, payload, task);
            throw new IllegalStateException("该实例已有操作正在进行，请等待完成后重试");
        }
        try {
            executor.execute(() -> {
                try {
                    Map<String, Object> result = task.call();
                    String message = result == null ? "操作完成" : String.valueOf(result.getOrDefault("message", "操作完成"));
                    String taskStatus = result == null ? "SUCCEEDED" : String.valueOf(result.getOrDefault("status", "SUCCEEDED"));
                    String finalStatus = Set.of("FAILED", "UNKNOWN").contains(taskStatus)
                            ? taskStatus : "SUCCEEDED";
                    mapper.finish(id, finalStatus, message, JSON.toJSONString(result), System.currentTimeMillis());
                } catch (Exception e) {
                    // Do not persist or log raw SDK messages (signed requests may contain credentials).
                    mapper.finish(id, "FAILED", "AWS 操作失败，请检查权限、实例状态和配置", null, System.currentTimeMillis());
                }
            });
        } catch (RuntimeException rejected) {
            mapper.finish(id, "FAILED", "任务队列繁忙，未执行 AWS 操作", null, System.currentTimeMillis());
        }
        return get(id);
    }

    public Map<String, Object> get(String id) {
        AwsOperation operation = id == null ? null : mapper.selectById(id);
        if (operation == null) throw new IllegalArgumentException("操作不存在");
        return view(operation);
    }

    public List<Map<String, Object>> list() {
        return mapper.selectList(new QueryWrapper<AwsOperation>().orderByDesc("created_at").last("LIMIT 100"))
                .stream().map(this::view).toList();
    }

    public void acknowledge(String id) { mapper.acknowledge(id); }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void recoverInterrupted() {
        long now = System.currentTimeMillis();
        mapper.expire(now - 20 * 60_000L, now);
    }

    private Map<String, Object> view(AwsOperation value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationId", value.getId()); result.put("id", value.getId());
        result.put("accountId", value.getAccountId()); result.put("region", value.getRegion());
        result.put("instanceId", value.getInstanceId()); result.put("action", value.getAction());
        result.put("status", value.getStatus()); result.put("message", value.getMessage());
        result.put("createdAt", value.getCreatedAt()); result.put("finishedAt", value.getFinishedAt());
        result.put("result", value.getResultJson() == null ? null : JSON.parseObject(value.getResultJson()));
        return result;
    }

    private String requestHash(Object payload) {
        if (payload == null) return null;
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSON.toJSONBytes(payload));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("请求校验不可用"); }
    }
}
