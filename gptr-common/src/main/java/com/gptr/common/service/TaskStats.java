package com.gptr.common.service;

import com.gptr.common.task.TaskStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * OBS-1：观测台统计快照（近 24h 窗口：任务量 / 状态分布 / 累计花费）。
 *
 * <p>白名单类型（只含统计聚合，绝不携带 config 或任务明细）。
 *
 * @param since   统计窗口起点（含）
 * @param total   窗口内任务总数
 * @param costUsd 窗口内累计花费（数据库层 sum，可为 0）
 * @param byStatus 窗口内状态分布（未出现的状态不包含）
 */
public record TaskStats(
        OffsetDateTime since,
        long total,
        BigDecimal costUsd,
        Map<TaskStatus, Long> byStatus) {
}
