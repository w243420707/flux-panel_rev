package com.admin.common.dto;

import lombok.Data;
import lombok.ToString;

@Data
public class AwsAccountSaveDto {
    private Long id;
    private String name;
    @ToString.Exclude private String accessKeyId;
    @ToString.Exclude private String accessSecret;
    @ToString.Exclude private String secretAccessKey;
    @ToString.Exclude private String sessionToken;
    private String mfaSerialNumber;
    private String mfaSerial;
    @ToString.Exclude private String mfaSecret;
    private Boolean clearMfa;
    private String roleArn;
    @ToString.Exclude private String roleExternalId;
    private String roleSessionName;
    @ToString.Exclude private String mfaTokenCode;
    private String region;
    private String regions;
    @ToString.Exclude private String proxy;
    private Boolean clearProxy;
    private Boolean clearSessionToken;
    private Boolean clearRole;
    private Long groupId;
    private Long proxyId;
    private Boolean enabled;
    private Long expiresAt;
    private String remark;
    private String note;
    private String group;
    private String groupName;

    public String resolvedAccessSecret() {
        return hasText(accessSecret) ? accessSecret : secretAccessKey;
    }

    public String resolvedGroup() {
        return hasText(groupName) ? groupName : group;
    }

    public String resolvedMfaSerial() {
        return hasText(mfaSerialNumber) ? mfaSerialNumber : mfaSerial;
    }

    public String resolvedRegion() {
        if (hasText(region)) return region;
        if (hasText(regions)) return regions.split(",")[0].trim();
        return "";
    }

    public java.util.List<String> resolvedRegions() {
        String value = hasText(regions) ? regions : region;
        if (!hasText(value)) return java.util.List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).distinct().toList();
    }

    public String resolvedRemark() {
        return hasText(remark) ? remark : note;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
