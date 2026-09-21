package com.gptr.common.engine;

import com.gptr.common.task.TaskStage;

/**
 * 单个阶段的执行结果（payload 为阶段中间结果 JSON，写入 checkpoint）。
 *
 * <p>{@code costUsd} 为该阶段消耗的外部 API 成本（LLM/搜索），由底座核算进
 * 任务成本预算。
 */
public record StageResult(TaskStage stage, String payload, double costUsd) {

    public StageResult(TaskStage stage, String payload) {
        this(stage, payload, 0.0);
    }
}
