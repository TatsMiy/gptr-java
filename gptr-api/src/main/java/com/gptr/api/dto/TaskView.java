package com.gptr.api.dto;

import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 任务视图（查询/取消/重试/列表响应）。
 *
 * <p>白名单：config 整体永不返回；仅派生最小展示字段 {@code mode}。
 */
public record TaskView(
        UUID id,
        TaskStatus status,
        String query,
        String mode,
        int attempt,
        int maxAttempts,
        int stepsUsed,
        BigDecimal costSpentUsd,
        String resultRef,
        boolean partialResult,
        String errorCode,
        String errorDetail,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static TaskView from(ResearchTask t) {
        return new TaskView(
                t.getId(),
                t.getStatus(),
                t.getQuery(),
                modeOf(t.getConfig()),
                t.getAttempt(),
                t.getMaxAttempts(),
                t.getStepsUsed(),
                t.getCostSpentUsd(),
                t.getResultRef(),
                t.isPartialResult(),
                t.getErrorCode(),
                t.getErrorDetail(),
                t.getCreatedAt(),
                t.getStartedAt(),
                t.getFinishedAt());
    }

    /** config 白名单派生：仅读 mode 键 → "deep_research" | "flat"；其余字段一律不外泄。 */
    static String modeOf(String config) {
        try {
            JsonNode root = MAPPER.readTree(config == null ? "{}" : config);
            JsonNode mode = root.path("mode");
            return mode.isTextual() && "deep_research".equals(mode.asText())
                    ? "deep_research" : "flat";
        } catch (Exception e) {
            return "flat";
        }
    }
}
