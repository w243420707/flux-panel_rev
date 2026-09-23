package com.admin.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OciAccountTestResult {
    private boolean ok;
    private int instanceCount;
}
