package com.gptr.common.repository;

import com.gptr.common.task.TaskCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 阶段检查点仓库。 */
public interface TaskCheckpointRepository extends JpaRepository<TaskCheckpoint, com.gptr.common.task.TaskCheckpointId> {

    /** 某任务的全部检查点（按阶段顺序，供断点续跑判断已完成的阶段）。 */
    List<TaskCheckpoint> findByTaskIdOrderByStageAsc(UUID taskId);
}
