package com.admin.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.TableName;

/** Reusable EC2 launch/change template. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aws_ec2_instance_template")
public class AwsEc2InstanceTemplate extends BaseEntity {
    private Long accountId;
    private String region;
    private String availabilityZone;
    private String name;
    private String imageId;
    private String instanceType;
    private String keyName;
    private String subnetId;
    private String securityGroupIds;
    private String rootVolumeSize;
    private String architecture;
    private Integer diskGiB;
    private String passwordEncrypted;
    private String userData;
    private Boolean spot;
    private Boolean enableIpv6;
    private Boolean disablePublicIp;
    private Boolean staticIp;
    private String cidr;
    private String tagsJson;
    private String remark;
}
