package com.gptr.common.exception;

import java.util.UUID;

/** 任务不存在。 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(UUID taskId) {
        super("Task not found: " + taskId);
    }
}
