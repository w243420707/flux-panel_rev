package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

/**
 * Persistent all-site traffic counter.
 *
 * The table is a singleton row with id=1. Its counters are cumulative and
 * are intentionally independent of the periodic user and user-tunnel fields.
 */
@Data
public class SiteTraffic {

    @TableId(value = "id")
    private Long id;

    private Long totalInFlow;

    private Long totalOutFlow;

    private Long createdTime;

    private Long updatedTime;
}
