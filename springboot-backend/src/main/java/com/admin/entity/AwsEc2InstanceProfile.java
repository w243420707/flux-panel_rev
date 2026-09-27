package com.admin.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.TableName;

/** User-maintained metadata for an EC2 instance. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aws_ec2_instance_profile")
public class AwsEc2InstanceProfile extends BaseEntity {
    private Long accountId;
    private String region;
    private String instanceId;
    private String name;
    private String instanceType;
    private String imageId;
    private String subnetId;
    private String securityGroupIds;
    private String rootVolumeSize;
    private String userData;
    private String tagsJson;
    private String remark;
}
