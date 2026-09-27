package com.admin.common.dto;

import lombok.Data;
import lombok.ToString;

/** Request body for changing panel update settings. */
@Data
public class PanelUpdateSettingsRequest {
    private Boolean enabled;
    private Integer checkIntervalSeconds;
    @ToString.Exclude
    private String githubToken;
    private Boolean clearGithubToken;
}
