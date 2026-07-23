package com.admin.service;

import com.admin.common.lang.R;

public interface CloudflareDnsSyncService {
    R syncBinding(Long bindingId, String trigger);

    R syncAllBindings(String trigger);

    R syncDueBindings();

    R syncBindingsByTunnel(Long tunnelId, String trigger);

    R syncBindingsByNode(Long nodeId, String trigger);

    R deleteBindingAndRecords(Long bindingId);

    R deleteBindingsByTunnel(Long tunnelId);
}
