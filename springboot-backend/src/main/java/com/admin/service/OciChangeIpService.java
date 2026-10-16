package com.admin.service;

public interface OciChangeIpService {
    void requestAfterUnreachableReport(Long nodeId);

    default void requestAfterUnreachableReport(Long nodeId, String reportedHost, Long checkedAt) {
        requestAfterUnreachableReport(nodeId);
    }
}
