package com.admin.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OciInstanceView {
    private String instanceOcid;
    private String displayName;
    private String publicIp;
    private String lifecycleState;
}
