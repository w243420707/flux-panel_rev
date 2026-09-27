package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.util.List;

@Data
@TableName("aws_failover_config")
public class AwsFailoverConfig {
    private Long id;
    private Integer enabled;
    private Long backupGroupId;
    private Integer maxInstances;
    private String cron;
    private String conditionsJson;
    private Integer telegramEnabled;
    private String nodeIdsJson;
    private Long templateId;
    private String accountSelection;
    private Integer requiredVcpu;
    private Long trafficLimitBytes;
    private String telegramBotTokenEncrypted;
    private String telegramChatId;
    @TableField("next_check_at") private Long nextCheckAt;
    @TableField("lease_until") private Long leaseUntil;

    public List<String> getConditions() {
        return conditionsJson == null ? List.of() : com.alibaba.fastjson2.JSON.parseArray(conditionsJson, String.class);
    }
}
