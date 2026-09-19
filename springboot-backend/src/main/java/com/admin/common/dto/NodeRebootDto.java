package com.admin.common.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Data
public class NodeRebootDto {
    @NotNull(message = "节点ID不能为空")
    @Positive(message = "节点ID必须大于0")
    private Long id;

    @JsonSetter("id")
    public void setId(BigDecimal value) {
        // Jackson's default Long deserializer truncates fractions, which could reboot a different node.
        id = value == null ? null : value.longValueExact();
    }
}
