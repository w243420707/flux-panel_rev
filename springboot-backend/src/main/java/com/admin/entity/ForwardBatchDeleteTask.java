package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 转发批量删除任务表
 * <p>
 * 用于异步批量删除转发配置，避免同步请求超时。
 * <p>
 * 独立自管理字段，不继承 BaseEntity 以免字段冲突（parent 已有 status/createdTime/updatedTime）。
 *
 * @author w243420707
 * @since 2026-10-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("forward_batch_delete_task")
public class ForwardBatchDeleteTask {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 任务唯一标识（UUID）
     */
    @TableField("task_id")
    private String taskId;

    /**
     * 操作用户 ID
     */
    @TableField("created_by")
    private Integer userId;

    /**
     * 待删除的转发 ID 列表（JSON 数组字符串）
     */
    @TableField("forward_ids")
    private String idsJson;

    /**
     * 是否强制删除
     */
    private Boolean force;

    /**
     * 任务状态：0=pending, 1=running, 2=success, 3=partial_fail, 4=fail
     */
    @TableField("status")
    private Integer taskStatus;

    /**
     * 总数
     */
    private Integer total;

    /**
     * 成功数
     */
    private Integer success;

    /**
     * 失败数
     */
    private Integer fail;

    /**
     * 已处理数
     */
    private Integer processed;

    /**
     * 失败明细（JSON 数组，含 id 和 reason）
     */
    @TableField("fail_reasons")
    private String failReasonsJson;

    /**
     * 创建时间（时间戳）
     */
    @TableField("created_at")
    private Long createdAt;

    /**
     * 完成时间（时间戳）
     */
    @TableField("finished_at")
    private Long finishedAt;
}
