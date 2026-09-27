package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

@Data
@TableName("aws_failover_job")
public class AwsFailoverJob {
    @TableId(type = IdType.INPUT)
    private String id;
    @TableField("source_key") private String sourceKey;
    private Long nodeId;
    @TableField("old_account_id") private Long oldAccountId;
    @TableField("old_instance_id") private String oldInstanceId;
    @TableField("old_region") private String oldRegion;
    @TableField("new_account_id") private Long newAccountId;
    @TableField("new_instance_id") private String newInstanceId;
    @TableField("new_region") private String newRegion;
    private String reason;
    private String status;
    private String message;
    @TableField("created_at") private Long createdAt;
    @TableField("finished_at") private Long finishedAt;
}
