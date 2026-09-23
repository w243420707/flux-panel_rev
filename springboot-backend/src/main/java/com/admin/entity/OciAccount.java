package com.admin.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class OciAccount extends BaseEntity {

    private String name;

    private String userOcid;

    private String tenancyOcid;

    private String fingerprint;

    private String region;

    private String privateKeyEncrypted;
}
