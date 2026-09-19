package com.admin.common.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class NodeUsageConfirmDto extends NodeRebootDto {
    @Positive(message = "统计记录ID必须大于0")
    private Long expectedUsageId;

    @NotBlank(message = "待确认的 VPS 标识不能为空")
    private String candidateId;

    @NotBlank(message = "请选择保留统计或更换 VPS")
    @Pattern(regexp = "same|replace", message = "请选择保留统计或更换 VPS")
    private String decision;

    @JsonSetter("expectedUsageId")
    public void readExpectedUsageId(BigDecimal value) {
        expectedUsageId = value == null ? null : value.longValueExact();
    }
}
