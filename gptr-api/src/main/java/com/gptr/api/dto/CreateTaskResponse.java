package com.gptr.api.dto;

import com.gptr.common.task.TaskStatus;

import java.util.UUID;

/** 创建任务响应（202 Accepted）。 */
public record CreateTaskResponse(UUID taskId, TaskStatus status) {
}
