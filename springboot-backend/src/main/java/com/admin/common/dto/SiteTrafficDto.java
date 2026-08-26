package com.admin.common.dto;

import lombok.Data;

/**
 * Cumulative traffic visible to administrators.
 */
@Data
public class SiteTrafficDto {

    private Long totalInFlow;

    private Long totalOutFlow;

    private Long totalFlow;

    public SiteTrafficDto(Long totalInFlow, Long totalOutFlow) {
        this.totalInFlow = totalInFlow;
        this.totalOutFlow = totalOutFlow;
        this.totalFlow = Math.addExact(totalInFlow, totalOutFlow);
    }
}
