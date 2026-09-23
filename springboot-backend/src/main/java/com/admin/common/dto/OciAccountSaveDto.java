package com.admin.common.dto;

import lombok.Data;

@Data
public class OciAccountSaveDto {
    private Long id;
    private String name;
    private String configText;
    private String privateKey;
}
