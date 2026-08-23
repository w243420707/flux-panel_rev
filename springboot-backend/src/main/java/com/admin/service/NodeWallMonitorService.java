package com.admin.service;

import com.admin.common.lang.R;

public interface NodeWallMonitorService {
    R markNodeUnavailableByExternalProbe(Long nodeId, String message);

    R markNodeAvailableByExternalProbe(Long nodeId, String message);
}
