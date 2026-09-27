package com.admin.service;

import com.admin.common.dto.PanelUpdateSettingsRequest;
import com.admin.common.lang.R;

public interface PanelUpdateService {
    R getSettings();

    R saveSettings(PanelUpdateSettingsRequest request);

    R checkNow();

    R getStatus();
}
