package com.gptr.common.task;

/**
 * 非法状态转换异常：状态机拒绝了不允许的 {@code (状态, 事件)} 组合。
 *
 * <p>所有状态写操作都必须经过 {@link TaskStateMachine#transition}，
 * 收到本异常说明存在并发竞态或逻辑错误，应记审计日志。
 */
public class TaskStateTransitionException extends RuntimeException {

    public TaskStateTransitionException(TaskStatus from, TaskEventType event) {
        super("Illegal task state transition: " + from + " + " + event);
    }
}
