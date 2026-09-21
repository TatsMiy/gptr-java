package com.gptr.common.task;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** 检查点复合主键 (task_id, stage)：每阶段一条记录，支持断点续跑。 */
public class TaskCheckpointId implements Serializable {

    private UUID taskId;
    private TaskStage stage;

    public TaskCheckpointId() {
    }

    public TaskCheckpointId(UUID taskId, TaskStage stage) {
        this.taskId = taskId;
        this.stage = stage;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public TaskStage getStage() {
        return stage;
    }

    public void setStage(TaskStage stage) {
        this.stage = stage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskCheckpointId that)) {
            return false;
        }
        return Objects.equals(taskId, that.taskId) && stage == that.stage;
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, stage);
    }
}
