package com.admin.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.ToString;

/** Stored AWS account metadata. Secret fields are always encrypted at rest. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aws_account")
public class AwsAccount extends BaseEntity {
    private String name;
    @ToString.Exclude private String accessKeyId;
    @JsonIgnore @ToString.Exclude
    private String accessKeyEncrypted;
    @JsonIgnore @ToString.Exclude
    private String accessSecretEncrypted;
    @JsonIgnore @ToString.Exclude
    private String sessionTokenEncrypted;
    private String mfaSerialNumber;
    @JsonIgnore @ToString.Exclude
    private String mfaSecretEncrypted;
    @JsonIgnore @ToString.Exclude
    private String proxyEncrypted;
    private String roleArn;
    @JsonIgnore @ToString.Exclude private String roleExternalIdEncrypted;
    private String roleSessionName;
    @TableField(exist = false)
    @JsonIgnore @ToString.Exclude
    private String roleExternalId;
    @TableField(exist = false)
    @JsonIgnore @ToString.Exclude
    private String proxyResolved;
    @TableField(exist = false)
    @JsonIgnore @ToString.Exclude
    private String mfaTokenCode;
    @TableField(exist = false)
    @JsonIgnore @ToString.Exclude
    private String accessKeyResolved;
    private String region;
    private String regions;
    private String proxy;
    private String remark;
    private String groupName;
    private Long groupId;
    private Long proxyPoolId;
    /** Epoch milliseconds; null means the account never expires. */
    private Long expiresAt;

    /** Compatibility aliases for callers that use AWS's standard credential names. */
    @JsonIgnore
    public String getSecretAccessKeyEncrypted() {
        return accessSecretEncrypted;
    }

    public void setSecretAccessKeyEncrypted(String value) {
        this.accessSecretEncrypted = value;
    }

    public String getMfaSerial() {
        return mfaSerialNumber;
    }

    public void setMfaSerial(String value) {
        this.mfaSerialNumber = value;
    }
}
