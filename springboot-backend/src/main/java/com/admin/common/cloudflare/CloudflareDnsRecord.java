package com.admin.common.cloudflare;

import lombok.Data;

@Data
public class CloudflareDnsRecord {
    private String id;
    private String name;
    private String type;
    private String content;
    private Integer ttl;
    private Boolean proxied;
    private String comment;
}
