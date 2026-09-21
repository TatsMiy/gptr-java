package com.gptr.api.dto;

import com.gptr.common.task.TaskEvent;
import com.gptr.common.task.TaskEventType;
import com.gptr.common.task.TaskStage;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 任务事件视图（含 taskId）。
 *
 * <p>taskId 由调用方传入（事件实体的 {@code task} 关联是懒加载，不能在事务外访问）。
 */
public record TaskEventView(
        UUID taskId,
        int seq,
        TaskEventType type,
        TaskStage stage,
        String payload,
        OffsetDateTime createdAt) {

    public static TaskEventView from(UUID taskId, TaskEvent e) {
        return new TaskEventView(taskId, e.getSeq(), e.getType(), e.getStage(), e.getPayload(), e.getCreatedAt());
    }
}
