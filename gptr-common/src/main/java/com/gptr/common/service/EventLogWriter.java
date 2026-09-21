package com.gptr.common.service;

import com.gptr.common.repository.TaskEventRepository;
import com.gptr.common.repository.TaskRepository;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskEvent;
import com.gptr.common.task.TaskEventType;
import com.gptr.common.task.TaskStage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * 事件日志写入器：append-only {@code task_events}。
 *
 * <p>事件序号按任务内自增（max(seq)+1）；写入应与状态更新同一事务
 * （由调用方的事务边界保证，见 {@code @Transactional} 传播）。
 */
@Service
@RequiredArgsConstructor
public class EventLogWriter {

    /** ACTIVITY 事件 seq 乐观重试的最大次数（同 seq 并发冲突时重查重试）。 */
    private static final int ACTIVITY_SEQ_MAX_ATTEMPTS = 5;

    private final TaskEventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final PlatformTransactionManager transactionManager;

    /** 追加一条事件；返回写入后的事件（含序号）。 */
    @Transactional
    public TaskEvent append(UUID taskId, TaskEventType type, TaskStage stage, String payload) {
        ResearchTask task = taskRepository.getReferenceById(taskId);
        int seq = eventRepository.maxSeq(taskId) + 1;

        TaskEvent event = new TaskEvent();
        event.setTask(task);
        event.setSeq(seq);
        event.setType(type);
        event.setStage(stage);
        event.setPayload(payload);
        return eventRepository.save(event);
    }

    /**
     * OBS-1：追加 ACTIVITY（观测）事件——独立事务（REQUIRES_NEW）+ seq 乐观重试。
     *
     * <p>图内并行节点可能并发写同一任务：不用应用层锁（UUID 对象锁与"锁内取号、
     * 锁外提交"都防不住竞态），改为 DB 唯一约束兜底——(task_id, seq) 冲突时该事务
     * 回滚并重查 maxSeq 重试（≤{@value #ACTIVITY_SEQ_MAX_ATTEMPTS} 次）。观测事件与
     * 任务状态事务解耦：失败不影响任务。
     *
     * <p>仅"唯一约束冲突"（duplicate key）会重试；其它完整性错误（如 CHECK 约束
     * 违反，通常是 schema 与枚举不同步）立即抛出，让接线错误立刻可见而非静默掩埋。
     *
     * @return 写入后的事件（重试耗尽仍冲突 → 抛异常，调用方吞掉）
     */
    public TaskEvent appendActivity(UUID taskId, TaskStage stage, String payload) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < ACTIVITY_SEQ_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    ResearchTask task = taskRepository.getReferenceById(taskId);
                    int seq = eventRepository.maxSeq(taskId) + 1;
                    TaskEvent event = new TaskEvent();
                    event.setTask(task);
                    event.setSeq(seq);
                    event.setType(TaskEventType.ACTIVITY);
                    event.setStage(stage);
                    event.setPayload(payload);
                    return eventRepository.save(event);
                });
            } catch (DataIntegrityViolationException e) {
                String msg = e.getMostSpecificCause() == null
                        ? "" : String.valueOf(e.getMostSpecificCause().getMessage());
                if (!msg.contains("duplicate key")) {
                    throw e; // 非并发冲突（如 CHECK 违反）→ 立即暴露
                }
                last = e; // 同 seq 并发冲突 → 下一轮重查重试
            }
        }
        throw last;
    }
}
