package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

/** Persistent settings and the last observed state for panel self-updates. */
@Data
@TableName("panel_update_setting")
public class PanelUpdateSetting {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Integer enabled;

    @TableField("check_interval_seconds")
    private Integer checkIntervalSeconds;

    @TableField(value = "github_token_encrypted", updateStrategy = FieldStrategy.IGNORED)
    private String githubTokenEncrypted;

    private String repository;

    @TableField("current_version")
    private String currentVersion;

    @TableField("latest_version")
    private String latestVersion;

    @TableField("current_commit")
    private String currentCommit;

    @TableField("latest_commit")
    private String latestCommit;

    @TableField("latest_commit_message")
    private String latestCommitMessage;

    @TableField("update_available")
    private Integer updateAvailable;

    @TableField("last_check_at")
    private Long lastCheckAt;

    @TableField("last_check_status")
    private String lastCheckStatus;

    @TableField("last_check_message")
    private String lastCheckMessage;

    @TableField("next_check_at")
    private Long nextCheckAt;

    @TableField("update_request_id")
    private String updateRequestId;

    @TableField("update_request_tag")
    private String updateRequestTag;

    @TableField("update_requested_at")
    private Long updateRequestedAt;
}
