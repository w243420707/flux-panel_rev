package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aws_share_group")
public class AwsShareGroup extends BaseEntity {
    private String name;
    private Long expiresAt;
    private String permissions;
    private String nodeIdsJson;
    private String instanceRefsJson;
    private String accessToken;
}
