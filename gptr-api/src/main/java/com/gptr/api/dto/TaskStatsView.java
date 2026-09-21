package com.gptr.api.dto;

import com.gptr.common.service.TaskStats;
import com.gptr.common.task.TaskStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * OBS-1：观测台统计响应（近 24h 聚合；API 层 5s 缓存）。
 */
public record TaskStatsView(
        OffsetDateTime since,
        long tasks24h,
        BigDecimal cost24hUsd,
        Map<TaskStatus, Long> byStatus) {

    public static TaskStatsView from(TaskStats s) {
        return new TaskStatsView(s.since(), s.total(), s.costUsd(), s.byStatus());
    }
}
