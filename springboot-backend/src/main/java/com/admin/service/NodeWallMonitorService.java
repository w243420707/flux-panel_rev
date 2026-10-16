package com.admin.service;

import com.admin.common.lang.R;

public interface NodeWallMonitorService {
    R markNodeUnavailableByExternalProbe(Long nodeId, String message);

    R markNodeAvailableByExternalProbe(Long nodeId, String message);

    default R markNodeUnavailableByExternalProbe(Long nodeId, String message, String reportedHost, Long checkedAt) {
        return markNodeUnavailableByExternalProbe(nodeId, message);
    }

    default R markNodeAvailableByExternalProbe(Long nodeId, String message, String reportedHost, Long checkedAt) {
        return markNodeAvailableByExternalProbe(nodeId, message);
    }
}
