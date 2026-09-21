package com.gptr.common.service;

import com.gptr.common.exception.TaskNotFoundException;
import com.gptr.common.repository.TaskEventRepository;
import com.gptr.common.repository.TaskRepository;
import com.gptr.common.task.ResearchTask;
import com.gptr.common.task.TaskEvent;
import com.gptr.common.task.TaskEventType;
import com.gptr.common.task.TaskStage;
import com.gptr.common.task.TaskStateMachine;
import com.gptr.common.task.TaskStateTransitionException;
import com.gptr.common.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 任务服务：提交（幂等）、取消、重试、查询、状态转换（含事件日志）。
 *
 * <p>所有状态写操作都经过 {@link TaskStateMachine#transition} 校验，非法转换抛
 * {@link com.gptr.common.task.TaskStateTransitionException}（API 层转 409）。
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    /** 任务列表分页的默认页大小（调用方未指定时——见 {@link #list} 的 {@code limit <= 0} 分支）。 */
    private static final int DEFAULT_PAGE_LIMIT = 30;

    /** 任务列表分页的页大小上限（防一次拉全表）。 */
    private static final int MAX_PAGE_LIMIT = 100;

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final EventLogWriter eventLog;

    /** 提交新任务（clientKey 幂等：重复提交返回已存在任务）。 */
    @Transactional
    public ResearchTask create(CreateTaskCommand cmd) {
        if (cmd.query() == null || cmd.query().isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (cmd.clientKey() != null && taskRepository.existsByClientKey(cmd.clientKey())) {
            return taskRepository.findByClientKey(cmd.clientKey()).orElseThrow();
        }

        ResearchTask task = new ResearchTask();
        task.setQuery(cmd.query());
        String config = cmd.config() == null ? "{}" : cmd.config();
        task.setConfig(config);
        task.setClientKey(cmd.clientKey());
        applyBudgets(task, config);
        taskRepository.save(task); // @PrePersist 计算 deadlineAt / createdAt
        eventLog.append(task.getId(), TaskEventType.CREATED, null, null);
        return task;
    }

    /** 从 config JSON 的 {@code budgets} 节点读取任务级预算。 */
    private void applyBudgets(ResearchTask task, String config) {
        try {
            JsonNode budgets = new ObjectMapper().readTree(config).path("budgets");
            if (budgets.isMissingNode() || !budgets.isObject()) {
                return;
            }
            if (budgets.hasNonNull("steps")) {
                task.setStepBudget(budgets.get("steps").asInt());
            }
            if (budgets.hasNonNull("timeSeconds")) {
                task.setTimeBudgetSeconds(budgets.get("timeSeconds").asInt());
            }
            if (budgets.hasNonNull("costUsd")) {
                task.setCostBudgetUsd(BigDecimal.valueOf(budgets.get("costUsd").asDouble()));
            }
        } catch (Exception ignored) {
            // 配置解析失败时使用默认预算
        }
    }

    /** 取消任务（PENDING / RUNNING → CANCELLED）。原子吊销租约（fence），
     *  被取消任务的 zombie worker 后续写（事件/checkpoint/成本）被 owner_token 门拒绝。 */
    @Transactional
    public ResearchTask cancel(UUID taskId) {
        ResearchTask task = get(taskId);
        if (!TaskStateMachine.canTransition(task.getStatus(), TaskEventType.CANCELLED)) {
            throw new TaskStateTransitionException(task.getStatus(), TaskEventType.CANCELLED);
        }
        task.setOwnerToken(null);
        task.setLeaseUntil(null);
        task.setStatus(TaskStatus.CANCELLED);
        task.setFinishedAt(OffsetDateTime.now());
        taskRepository.save(task);
        eventLog.append(taskId, TaskEventType.CANCELLED, null, null);
        return task;
    }

    /**
     * 运维重试：FAILED → PENDING（C3-S4 修复）。
     *
     * <p>重试 = 全新运行语义：清错误/结果引用，重置 stepsUsed/costSpentUsd，
     * 重算 deadlineAt（否则预算/超时失败重试在首个阶段必再 BUDGET_* 失败），
     * 吊销残留租约。状态转换经 {@link TaskStateMachine}（FAILED+RETRY→PENDING）。
     */
    @Transactional
    public ResearchTask retry(UUID taskId) {
        ResearchTask task = get(taskId);
        if (task.getStatus() != TaskStatus.FAILED) {
            throw new IllegalStateException("Only FAILED tasks can be retried, current=" + task.getStatus());
        }
        if (!TaskStateMachine.canTransition(TaskStatus.FAILED, TaskEventType.RETRY)) {
            throw new TaskStateTransitionException(TaskStatus.FAILED, TaskEventType.RETRY);
        }
        task.setAttempt(0);
        task.setStepsUsed(0);
        task.setCostSpentUsd(BigDecimal.ZERO);
        task.setErrorCode(null);
        task.setErrorDetail(null);
        task.setFinishedAt(null);
        task.setResultRef(null);
        task.setPartialResult(false);
        task.setOwnerToken(null);
        task.setLeaseUntil(null);
        task.setDeadlineAt(OffsetDateTime.now().plusSeconds(task.getTimeBudgetSeconds()));
        // 状态经状态机转换（FAILED + RETRY → PENDING），事件落 RETRY
        transition(taskId, TaskEventType.RETRY, null, null);
        return get(taskId);
    }

    /** 状态转换（校验 + 落库 + 事件），供 worker/API 复用。 */
    @Transactional
    public ResearchTask transition(UUID taskId, TaskEventType event, TaskStage stage, String payload) {
        ResearchTask task = get(taskId);
        TaskStatus next = TaskStateMachine.transition(task.getStatus(), event);
        task.setStatus(next);
        if (TaskStateMachine.isTerminal(next)) {
            task.setFinishedAt(OffsetDateTime.now());
        }
        taskRepository.save(task);
        eventLog.append(taskId, event, stage, payload);
        return task;
    }

    /**
     * worker 完成成功：写 result_ref 后置 SUCCEEDED。
     * ownerToken 不匹配（租约已被回收）则静默跳过，由 LeaseReaper 负责。
     */
    @Transactional
    public void completeSuccess(UUID taskId, UUID ownerToken, String resultRef) {
        ResearchTask task = get(taskId);
        if (!ownerToken.equals(task.getOwnerToken())) {
            return;
        }
        task.setResultRef(resultRef);
        taskRepository.save(task);
        transition(taskId, TaskEventType.SUCCEEDED, null, null);
    }

    /**
     * worker 失败：写错误信息后置 FAILED。
     * ownerToken 不匹配则静默跳过。
     */
    @Transactional
    public void fail(UUID taskId, UUID ownerToken, String errorCode, String errorDetail) {
        ResearchTask task = get(taskId);
        if (!ownerToken.equals(task.getOwnerToken())) {
            return;
        }
        task.setErrorCode(errorCode);
        task.setErrorDetail(errorDetail);
        taskRepository.save(task);
        transition(taskId, TaskEventType.FAILED, null, null);
    }

    /**
     * 预算耗尽优雅终止：置 FAILED + {@code BUDGET_<KIND>}，
     * 标记 partial_result=true（保留已写 checkpoint 的中间结果）。
     */
    @Transactional
    public void budgetExceeded(UUID taskId, UUID ownerToken, String budgetKind) {
        ResearchTask task = get(taskId);
        if (!ownerToken.equals(task.getOwnerToken())) {
            return;
        }
        task.setPartialResult(true);
        task.setErrorCode("BUDGET_" + budgetKind);
        task.setErrorDetail("budget " + budgetKind + " exceeded");
        taskRepository.save(task);
        transition(taskId, TaskEventType.BUDGET_EXCEEDED, null, null);
    }

    /** worker 核算阶段成本：累加 cost_spent_usd（事务内，与读取同事务）。 */
    @Transactional
    public void recordCost(UUID taskId, UUID ownerToken, double costUsd) {
        ResearchTask task = get(taskId);
        if (!ownerToken.equals(task.getOwnerToken())) {
            return;
        }
        task.setCostSpentUsd(task.getCostSpentUsd()
                .add(BigDecimal.valueOf(costUsd)));
        taskRepository.save(task);
    }

    /** 查询任务，不存在抛 {@link TaskNotFoundException}。 */
    @Transactional(readOnly = true)
    public ResearchTask get(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /** 任务事件列表（seq 升序）。 */
    @Transactional(readOnly = true)
    public List<TaskEvent> listEvents(UUID taskId) {
        return eventRepository.findByTaskIdOrderBySeqAsc(taskId);
    }

    /**
     * OBS-1/2：任务列表（created_at DESC；status 空 = 全部；offset 翻页，limit 1..100，默认 30）。
     *
     * <p>评审修正：{@link org.springframework.data.domain.PageRequest#of(int, int)} 首参是
     * <b>页号</b>（0-based），SQL 偏移 = pageNumber × size——故 offset 必须先折算为页号
     * （{@code offset / limit}），否则 offset=30 会被当成第 30 页产生 OFFSET 900 跳页。
     * 约定：offset 应为 limit 的整数倍（前端恒以页大小步进）；非整倍余数向下折算。
     */
    @Transactional(readOnly = true)
    public List<ResearchTask> list(TaskStatus status, int offset, int limit) {
        int n = limit <= 0 ? DEFAULT_PAGE_LIMIT : Math.min(limit, MAX_PAGE_LIMIT);
        int off = Math.max(0, offset);
        org.springframework.data.domain.Pageable page =
                org.springframework.data.domain.PageRequest.of(off / n, n);
        return status == null
                ? taskRepository.findLatest(page)
                : taskRepository.findLatestByStatus(status, page);
    }

    /** OBS-1：近 24h 统计快照。 */
    @Transactional(readOnly = true)
    public TaskStats statsSince(OffsetDateTime since) {
        java.util.Map<TaskStatus, Long> by = new java.util.LinkedHashMap<>();
        for (Object[] row : taskRepository.countGroupByStatusSince(since)) {
            by.put((TaskStatus) row[0], (Long) row[1]);
        }
        BigDecimal cost = taskRepository.sumCostSince(since);
        return new TaskStats(since, taskRepository.countSince(since),
                cost == null ? BigDecimal.ZERO : cost, by);
    }
}
