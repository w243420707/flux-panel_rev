package com.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Durable idempotency/result record. Never persists credentials or launch scripts. */
@Data
@TableName("aws_operation")
public class AwsOperation {
    @TableId(type = IdType.INPUT)
    private String id;
    private String activeTarget;
    private Long accountId;
    private String region;
    private String instanceId;
    private String action;
    private String requestHash;
    private String status;
    private String message;
    private String resultJson;
    private Long createdAt;
    private Long finishedAt;
}
