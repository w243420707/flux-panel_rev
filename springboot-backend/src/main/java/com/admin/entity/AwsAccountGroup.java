package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aws_account_group")
public class AwsAccountGroup extends BaseEntity {
    private String name;
    private String remark;
}
