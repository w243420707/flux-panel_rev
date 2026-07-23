package com.admin.common.cloudflare;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudflareDnsTarget {
    private Long nodeId;
    private String recordType;
    private String content;
}
