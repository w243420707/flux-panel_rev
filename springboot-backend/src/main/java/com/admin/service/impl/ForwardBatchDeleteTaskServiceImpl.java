package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.Forward;
import com.admin.entity.ForwardBatchDeleteTask;
import com.admin.entity.User;
import com.admin.mapper.ForwardBatchDeleteTaskMapper;
import com.admin.service.ForwardBatchDeleteTaskService;
import com.admin.service.ForwardService;
import com.admin.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 转发批量删除任务服务实现
 * <p>
 * 异步执行批量删除，避免同步请求超时。
 *
 * @author w243420707
 * @since 2026-10-16
 */
@Slf4j
@Service
public class ForwardBatchDeleteTaskServiceImpl
        extends ServiceImpl<ForwardBatchDeleteTaskMapper, ForwardBatchDeleteTask>
        implements ForwardBatchDeleteTaskService {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_RUNNING = 1;
    private static final int STATUS_SUCCESS = 2;
    private static final int STATUS_FAIL = 3;
    private static final int STATUS_PARTIAL_FAIL = 4;

    private static final long TASK_EXPIRE_MILLIS = 24 * 3600 * 1000L;

    @Resource
    private ForwardService forwardService;

    @Resource
    private UserService userService;

    @Resource(name = "forwardConfigExecutor")
    private Executor forwardConfigExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public R submitBatchDelete(List<Long> ids, boolean force) {
        List<Long> normalizedIds = normalizeIds(ids);
        if (normalizedIds.isEmpty()) {
            return R.err("请选择需要删除的转发");
        }

        UserInfo currentUser = getCurrentUserInfo();
        String taskId = UUID.randomUUID().toString().replace("-", "");

        ForwardBatchDeleteTask task = new ForwardBatchDeleteTask();
        task.setTaskId(taskId);
        task.setUserId(currentUser.getUserId());
        task.setIdsJson(toJson(normalizedIds));
        task.setForce(force);
        task.setTaskStatus(STATUS_PENDING);
        task.setTotal(normalizedIds.size());
        task.setSuccess(0);
        task.setFail(0);
        task.setProcessed(0);
        long now = System.currentTimeMillis();
        task.setCreatedAt(now);
        task.setFinishedAt(now);
        this.save(task);

        log.info("批量删除任务已提交 [taskId={}, userId={}, total={}, force={}]",
                taskId, currentUser.getUserId(), normalizedIds.size(), force);

        forwardConfigExecutor.execute(() -> processBatchDeleteTask(taskId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("total", normalizedIds.size());
        return R.ok(data);
    }

    @Override
    public R getBatchDeleteProgress(String taskId) {
        ForwardBatchDeleteTask task = this.getOne(
                new LambdaQueryWrapper<ForwardBatchDeleteTask>()
                        .eq(ForwardBatchDeleteTask::getTaskId, taskId)
                        .eq(ForwardBatchDeleteTask::getUserId, JwtUtil.getUserIdFromToken()));
        if (task == null) {
            return R.err("任务不存在");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("status", task.getTaskStatus());
        data.put("total", task.getTotal());
        data.put("processed", task.getProcessed());
        data.put("success", task.getSuccess());
        data.put("fail", task.getFail());
        data.put("force", task.getForce());
        data.put("failReasons", parseFailReasons(task.getFailReasonsJson()));
        return R.ok(data);
    }

    @Override
    public void processBatchDeleteTask(String taskId) {
        ForwardBatchDeleteTask task = this.getOne(
                new LambdaQueryWrapper<ForwardBatchDeleteTask>()
                        .eq(ForwardBatchDeleteTask::getTaskId, taskId));
        if (task == null) {
            log.warn("批量删除任务不存在 [taskId={}]", taskId);
            return;
        }

        if (task.getTaskStatus() != null && task.getTaskStatus() != STATUS_PENDING) {
            log.warn("批量删除任务状态异常，跳过执行 [taskId={}, status={}]", taskId, task.getTaskStatus());
            return;
        }

        updateTaskStatus(taskId, STATUS_RUNNING);

        List<Long> ids = parseIds(task.getIdsJson());
        boolean force = Boolean.TRUE.equals(task.getForce());
        User taskOwner = task.getUserId() == null ? null : userService.getById(task.getUserId());
        Integer roleId = taskOwner == null || taskOwner.getRoleId() == null ? 1 : taskOwner.getRoleId();
        UserInfo currentUser = new UserInfo(task.getUserId(), roleId);

        List<Forward> forwardList = forwardService.listByIds(ids);
        Map<Long, Forward> forwardMap = forwardList.stream()
                .collect(Collectors.toMap(Forward::getId, f -> f));

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Map<String, Object>> failures = Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Long id : ids) {
            futures.add(CompletableFuture.runAsync(() -> {
                Forward forward = forwardMap.get(id);
                if (forward == null) {
                    addFailure(failures, id, "端口转发不存在");
                    failCount.incrementAndGet();
                    return;
                }

                if (!canOperateForward(forward, currentUser)) {
                    addFailure(failures, id, "端口转发不存在");
                    failCount.incrementAndGet();
                    return;
                }

                try {
                    R result = force
                            ? forwardService.forceDeleteForwardForUser(id, currentUser.getUserId(), currentUser.getRoleId())
                            : forwardService.deleteForwardForUser(id, currentUser.getUserId(), currentUser.getRoleId());
                    if (result.getCode() == 0) {
                        successCount.incrementAndGet();
                    } else {
                        addFailure(failures, id, result.getMsg());
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("批量删除转发异常 [taskId={}, forwardId={}]", taskId, id, e);
                    addFailure(failures, id, "删除异常: " + e.getMessage());
                    failCount.incrementAndGet();
                }
            }, forwardConfigExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int total = ids.size();
        int success = successCount.get();
        int fail = failCount.get();
        int finalStatus = fail == 0 ? STATUS_SUCCESS : (success > 0 ? STATUS_PARTIAL_FAIL : STATUS_FAIL);

        updateTaskResult(taskId, finalStatus, success, fail, failures);

        log.info("批量删除任务完成 [taskId={}, total={}, success={}, fail={}, status={}]",
                taskId, total, success, fail, finalStatus);
    }

    @Override
    @Scheduled(fixedDelay = 3600000)
    public void cleanExpiredTasks() {
        long cutoff = System.currentTimeMillis() - TASK_EXPIRE_MILLIS;
        LambdaQueryWrapper<ForwardBatchDeleteTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(ForwardBatchDeleteTask::getCreatedAt, cutoff)
                .in(ForwardBatchDeleteTask::getTaskStatus, STATUS_SUCCESS, STATUS_PARTIAL_FAIL, STATUS_FAIL);
        int removed = this.baseMapper.delete(wrapper);
        if (removed > 0) {
            log.info("清理过期批量删除任务 {} 个", removed);
        }
    }

    private void updateTaskStatus(String taskId, int status) {
        LambdaUpdateWrapper<ForwardBatchDeleteTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ForwardBatchDeleteTask::getTaskId, taskId)
                .set(ForwardBatchDeleteTask::getTaskStatus, status)
                .set(ForwardBatchDeleteTask::getFinishedAt, System.currentTimeMillis());
        this.update(wrapper);
    }

    private void updateTaskResult(String taskId, int status, int success, int fail,
                                  List<Map<String, Object>> failures) {
        LambdaUpdateWrapper<ForwardBatchDeleteTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ForwardBatchDeleteTask::getTaskId, taskId)
                .set(ForwardBatchDeleteTask::getTaskStatus, status)
                .set(ForwardBatchDeleteTask::getSuccess, success)
                .set(ForwardBatchDeleteTask::getFail, fail)
                .set(ForwardBatchDeleteTask::getProcessed, success + fail)
                .set(ForwardBatchDeleteTask::getFailReasonsJson, toJson(failures))
                .set(ForwardBatchDeleteTask::getFinishedAt, System.currentTimeMillis());
        this.update(wrapper);
    }

    private void addFailure(List<Map<String, Object>> failures, Long id, String message) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("id", id);
        failure.put("message", message == null || message.isBlank() ? "删除失败" : message);
        failures.add(failure);
    }

    private List<Long> normalizeIds(List<Long> ids) {
        LinkedHashSet<Long> normalizedIds = new LinkedHashSet<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && id > 0) {
                    normalizedIds.add(id);
                }
            }
        }
        return new ArrayList<>(normalizedIds);
    }

    private List<Long> parseIds(String idsJson) {
        try {
            return objectMapper.readValue(idsJson, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.error("解析任务 ID 列表失败", e);
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> parseFailReasons(String failReasonsJson) {
        if (failReasonsJson == null || failReasonsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(failReasonsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("解析失败明细失败", e);
            return Collections.emptyList();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "[]";
        }
    }

    private UserInfo getCurrentUserInfo() {
        return new UserInfo(JwtUtil.getUserIdFromToken(), JwtUtil.getRoleIdFromToken());
    }

    private boolean canOperateForward(Forward forward, UserInfo currentUser) {
        return currentUser.getRoleId() == 0
                || Objects.equals(currentUser.getUserId(), forward.getUserId());
    }

    private static class UserInfo {
        private final Integer userId;
        private final Integer roleId;

        private UserInfo(Integer userId, Integer roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        private Integer getUserId() {
            return userId;
        }

        private Integer getRoleId() {
            return roleId;
        }
    }
}
