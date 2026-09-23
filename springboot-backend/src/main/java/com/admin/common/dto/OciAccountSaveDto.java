package com.admin.common.dto;

import lombok.Data;

@Data
public class OciAccountSaveDto {
    private Long id;
    private String name;
    private String userOcid;
    private String tenancyOcid;
    private String fingerprint;
    private String region;
    private String privateKey;
}
