package com.gptr.common.repository;

import com.gptr.common.task.TaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** 任务事件仓库（append-only 日志）。 */
public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {

    /** 按任务查询事件（seq 升序）。 */
    @Query("select e from TaskEvent e where e.task.id = :taskId order by e.seq asc")
    List<TaskEvent> findByTaskIdOrderBySeqAsc(@Param("taskId") UUID taskId);

    /** 任务当前最大事件序号（无事件返回 0）。 */
    @Query("select coalesce(max(e.seq), 0) from TaskEvent e where e.task.id = :taskId")
    int maxSeq(@Param("taskId") UUID taskId);
}
