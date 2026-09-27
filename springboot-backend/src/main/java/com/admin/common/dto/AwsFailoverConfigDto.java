package com.admin.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class AwsFailoverConfigDto {
    private Boolean enabled;
    private Long backupGroupId;
    private Integer maxInstances;
    private String cron;
    private List<String> conditions;
    private Boolean telegramEnabled;
    private List<Long> nodeIds;
    private Long templateId;
    private String accountSelection;
    private Integer requiredVcpu;
    private Long trafficLimitBytes;
    private String telegramBotToken;
    private String telegramChatId;
}
