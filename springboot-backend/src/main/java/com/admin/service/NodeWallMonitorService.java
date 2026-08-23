package com.admin.service;

import com.admin.common.lang.R;

public interface NodeWallMonitorService {

    void checkScheduledNodes();

    R checkNodeNow(Long nodeId);

    R markNodeUnavailableByExternalProbe(Long nodeId, String message);

    R markNodeAvailableByExternalProbe(Long nodeId, String message);
}
