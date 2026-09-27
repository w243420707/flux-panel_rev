package com.admin.common.dto;

import lombok.Data;

/** Request body for changing panel update settings. */
@Data
public class PanelUpdateSettingsRequest {
    private Boolean enabled;
    private Integer checkIntervalSeconds;
    private String githubToken;
    private Boolean clearGithubToken;
}
