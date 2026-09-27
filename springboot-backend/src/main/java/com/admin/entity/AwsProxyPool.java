package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aws_proxy_pool")
public class AwsProxyPool extends BaseEntity {
    private String name;
    private String endpoint;
    @JsonIgnore @ToString.Exclude
    private String proxyEncrypted;
    private String remark;
}
