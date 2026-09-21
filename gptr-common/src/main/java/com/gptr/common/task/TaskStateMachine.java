package com.gptr.common.task;

import java.util.EnumMap;
import java.util.Map;

/**
 * 任务状态机（状态转换表）。
 *
 * <p>纯函数、无副作用，可安全并发调用。所有任务状态写操作都必须经过
 * {@link #transition(TaskStatus, TaskEventType)}，非法转换抛
 * {@link TaskStateTransitionException}。
 *
 * <p>转换表（状态 × 事件 → 新状态）。事件名与 {@link TaskEventType} 枚举、
 * 数据库 {@code task_events.type} 的 CHECK 约束一致：
 * <pre>
 * PENDING  + DISPATCHED       → RUNNING
 * PENDING  + CANCELLED        → CANCELLED
 * RUNNING  + STAGE_COMPLETED  → RUNNING
 * RUNNING  + RETRY            → RUNNING
 * RUNNING  + SUCCEEDED        → SUCCEEDED
 * RUNNING  + FAILED           → FAILED
 * RUNNING  + BUDGET_EXCEEDED  → FAILED
 * RUNNING  + CANCELLED        → CANCELLED
 * RUNNING  + LEASE_EXPIRED    → PENDING
 * FAILED   + RETRY            → PENDING   （运维重试入状态机）
 * </pre>
 *
 * <p>STAGE_STARTED / CREATED / WEBHOOK_SENT 是纯日志事件，不触发状态转换。
 * SUSPENDED 目前无任何转换（预留 HITL，P1 引入）。
 */
public final class TaskStateMachine {

    private static final Map<TaskStatus, Map<TaskEventType, TaskStatus>> TRANSITIONS =
            new EnumMap<>(TaskStatus.class);

    static {
        put(TaskStatus.PENDING, TaskEventType.DISPATCHED, TaskStatus.RUNNING);
        put(TaskStatus.PENDING, TaskEventType.CANCELLED, TaskStatus.CANCELLED);

        put(TaskStatus.RUNNING, TaskEventType.STAGE_COMPLETED, TaskStatus.RUNNING);
        put(TaskStatus.RUNNING, TaskEventType.RETRY, TaskStatus.RUNNING);
        put(TaskStatus.RUNNING, TaskEventType.SUCCEEDED, TaskStatus.SUCCEEDED);
        put(TaskStatus.RUNNING, TaskEventType.FAILED, TaskStatus.FAILED);
        put(TaskStatus.RUNNING, TaskEventType.BUDGET_EXCEEDED, TaskStatus.FAILED);
        put(TaskStatus.RUNNING, TaskEventType.CANCELLED, TaskStatus.CANCELLED);
        put(TaskStatus.RUNNING, TaskEventType.LEASE_EXPIRED, TaskStatus.PENDING);
        // FAILED 运维重试 → 回队（转换表补齐，retry() 不再裸写状态）
        put(TaskStatus.FAILED, TaskEventType.RETRY, TaskStatus.PENDING);
    }

    private TaskStateMachine() {
        // 工具类，禁止实例化
    }

    /**
     * 执行一次状态转换。
     *
     * @param from  当前状态
     * @param event 事件
     * @return 新状态
     * @throws TaskStateTransitionException 非法转换
     */
    public static TaskStatus transition(TaskStatus from, TaskEventType event) {
        TaskStatus target = TRANSITIONS.getOrDefault(from, Map.of()).get(event);
        if (target == null) {
            throw new TaskStateTransitionException(from, event);
        }
        return target;
    }

    /** 该 {@code (状态, 事件)} 组合是否允许转换。 */
    public static boolean canTransition(TaskStatus from, TaskEventType event) {
        return TRANSITIONS.getOrDefault(from, Map.of()).containsKey(event);
    }

    /** 是否终态（终态不可逆）。 */
    public static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCEEDED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    private static void put(TaskStatus from, TaskEventType event, TaskStatus to) {
        TRANSITIONS.computeIfAbsent(from, k -> new EnumMap<>(TaskEventType.class))
                .put(event, to);
    }
}
