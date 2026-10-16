package com.admin.service;

import com.admin.common.lang.R;
import com.admin.entity.ForwardBatchDeleteTask;

import java.util.List;
import java.util.Map;

/**
 * 转发批量删除任务服务
 *
 * @author w243420707
 * @since 2026-10-16
 */
public interface ForwardBatchDeleteTaskService {

    /**
     * 提交批量删除任务
     * @param ids 转发 ID 列表
     * @param force 是否强制删除
     * @return 包含 task_id 的结果
     */
    R submitBatchDelete(List<Long> ids, boolean force);

    /**
     * 查询任务进度
     * @param taskId 任务 ID
     * @return 进度信息
     */
    R getBatchDeleteProgress(String taskId);

    /**
     * 执行批量删除任务
     * @param taskId 任务 ID
     */
    void processBatchDeleteTask(String taskId);

    /**
     * 清理过期任务（24h 前的终态任务）
     */
    void cleanExpiredTasks();
}
