package com.admin.entity;

import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;

/** AWS binding contract used by future APK/probe dispatch. */
@Data
@TableName("aws_node_binding")
public class AwsNodeBinding {
    @TableId(value = "node_id", type = IdType.INPUT)
    private Long nodeId;
    private Long accountId;
    private String instanceId;
    private String region;
    private Long updatedTime;
}
