package com.admin.service;

import com.admin.common.lang.R;

public interface NodeWallMonitorService {

    void checkScheduledNodes();

    R checkNodeNow(Long nodeId);
}
