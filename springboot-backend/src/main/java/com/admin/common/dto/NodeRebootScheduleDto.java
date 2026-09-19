package com.admin.common.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Digits;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class NodeRebootScheduleDto extends NodeRebootDto {
    @NotNull(message = "请输入重启间隔小时数")
    @Min(value = 0, message = "重启间隔不能小于0小时")
    @Max(value = 720, message = "重启间隔不能超过720小时")
    @Digits(integer = 3, fraction = 0, message = "重启间隔必须为整数小时")
    private BigDecimal intervalHours;
}
