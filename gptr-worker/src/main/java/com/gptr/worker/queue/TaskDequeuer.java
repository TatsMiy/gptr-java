package com.gptr.worker.queue;

import com.gptr.common.repository.TaskRepository;
import com.gptr.common.task.ResearchTask;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务队列出队器。
 *
 * <p>单条 UPDATE ... RETURNING 原子完成：状态置 RUNNING + 分配 owner_token
 * 租约令牌 + 租约到期时间 + attempt+1。多 worker 并发由
 * {@code FOR UPDATE SKIP LOCKED} 保证不重复出队。
 */
@Component
@RequiredArgsConstructor
public class TaskDequeuer {

    private final JdbcTemplate jdbcTemplate;
    private final TaskRepository taskRepository;

    @Value("${worker.lease-seconds:300}")
    private int leaseSeconds;

    /** 原子出队：有 PENDING 任务则返回并占用，否则返回空。 */
    public Optional<ResearchTask> dequeue() {
        String sql = "UPDATE tasks SET status = 'RUNNING'," +
                " owner_token = gen_random_uuid()," +
                " lease_until = now() + interval '" + leaseSeconds + " seconds'," +
                " attempt = attempt + 1," +
                " started_at = COALESCE(started_at, now())," +
                " updated_at = now()" +
                " WHERE id = (" +
                "   SELECT id FROM tasks" +
                "   WHERE status = 'PENDING' AND deadline_at > now()" +
                "   ORDER BY priority DESC, created_at ASC" +
                "   FOR UPDATE SKIP LOCKED LIMIT 1)" +
                " RETURNING id";

        List<UUID> ids = jdbcTemplate.query(sql, (rs, i) -> rs.getObject("id", UUID.class));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return taskRepository.findById(ids.get(0));
    }

    /** 续租：仅当任务仍归本 worker（owner_token 匹配）且 RUNNING 时生效。 */
    public boolean renewLease(UUID taskId, UUID ownerToken) {
        String sql = "UPDATE tasks SET lease_until = now() + interval '" + leaseSeconds + " seconds'," +
                " updated_at = now()" +
                " WHERE id = ? AND owner_token = ? AND status = 'RUNNING'";
        return jdbcTemplate.update(sql, taskId, ownerToken) == 1;
    }
}
