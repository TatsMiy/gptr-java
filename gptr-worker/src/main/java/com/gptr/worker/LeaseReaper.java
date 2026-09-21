package com.gptr.worker;

import com.gptr.common.repository.TaskRepository;
import com.gptr.common.service.EventLogWriter;
import com.gptr.common.service.TaskService;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskEventType;
import com.gptr.common.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 租约回收器：扫描 RUNNING 且租约过期的任务。
 *
 * <ul>
 *   <li>attempt &lt; max_attempts → 重置回 PENDING（回队重拾，attempt 已在出队时 +1）</li>
 *   <li>否则 → FAILED（error_code=LEASE_EXPIRED_EXHAUSTED）</li>
 * </ul>
 *
 * <p>worker 进程崩溃/机器宕机无需协调：租约到期后由本组件自动恢复。
 */
@Component
@RequiredArgsConstructor
public class LeaseReaper {

    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final EventLogWriter eventLog;
    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelayString = "${worker.reap-interval-ms:30000}")
    public void reap() {
        List<ResearchTask> stale = taskRepository.findByStatusAndLeaseUntilBefore(
                TaskStatus.RUNNING, OffsetDateTime.now());
        for (ResearchTask task : stale) {
            if (task.getAttempt() < task.getMaxAttempts()) {
                requeue(task.getId());
                eventLog.append(task.getId(), TaskEventType.LEASE_EXPIRED, null, null);
            } else {
                taskService.fail(task.getId(), task.getOwnerToken(),
                        "LEASE_EXPIRED_EXHAUSTED", "lease expired after max attempts");
            }
        }
    }

    private void requeue(UUID taskId) {
        jdbcTemplate.update(
                "UPDATE tasks SET status = 'PENDING', owner_token = NULL, lease_until = NULL," +
                        " updated_at = now() WHERE id = ? AND status = 'RUNNING'",
                taskId);
    }
}
