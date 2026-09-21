package com.gptr.common.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机转换表单测（覆盖全部合法转换 + 非法转换 + 终态不可逆）。
 *
 * <p>纯单测，无需 Spring 上下文。
 */
class TaskStateMachineTest {

    @Test
    void pendingDispatchedToRunning() {
        assertEquals(TaskStatus.RUNNING,
                TaskStateMachine.transition(TaskStatus.PENDING, TaskEventType.DISPATCHED));
    }

    @Test
    void pendingCancelled() {
        assertEquals(TaskStatus.CANCELLED,
                TaskStateMachine.transition(TaskStatus.PENDING, TaskEventType.CANCELLED));
    }

    @Test
    void runningCheckpointStaysRunning() {
        assertEquals(TaskStatus.RUNNING,
                TaskStateMachine.transition(TaskStatus.RUNNING, TaskEventType.STAGE_COMPLETED));
    }

    @Test
    void runningRetryStaysRunning() {
        assertEquals(TaskStatus.RUNNING,
                TaskStateMachine.transition(TaskStatus.RUNNING, TaskEventType.RETRY));
    }

    @Test
    void runningSucceed() {
        assertEquals(TaskStatus.SUCCEEDED,
                TaskStateMachine.transition(TaskStatus.RUNNING, TaskEventType.SUCCEEDED));
    }

    @Test
    void runningFail() {
        assertEquals(TaskStatus.FAILED,
                TaskStateMachine.transition(TaskStatus.RUNNING, TaskEventType.FAILED));
    }

    @Test
    void runningBudgetExceededToFailed() {
        assertEquals(TaskStatus.FAILED,
                TaskStateMachine.transition(TaskStatus.RUNNING, TaskEventType.BUDGET_EXCEEDED));
    }

    @Test
    void runningCancelled() {
        assertEquals(TaskStatus.CANCELLED,
                TaskStateMachine.transition(TaskStatus.RUNNING, TaskEventType.CANCELLED));
    }

    @Test
    void leaseExpiredBackToPending() {
        assertEquals(TaskStatus.PENDING,
                TaskStateMachine.transition(TaskStatus.RUNNING, TaskEventType.LEASE_EXPIRED));
    }

    @Test
    void terminalStatesRejectEveryEvent() {
        for (TaskStatus terminal : new TaskStatus[]{
                TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED}) {
            for (TaskEventType event : TaskEventType.values()) {
                // 例外：FAILED + RETRY → PENDING（运维重试入状态机）
                if (terminal == TaskStatus.FAILED && event == TaskEventType.RETRY) {
                    assertTrue(TaskStateMachine.canTransition(terminal, event),
                            "FAILED + RETRY 必须合法（C3-S4 运维重试）");
                    continue;
                }
                assertFalse(TaskStateMachine.canTransition(terminal, event),
                        terminal + " must reject " + event);
                assertThrows(TaskStateTransitionException.class,
                        () -> TaskStateMachine.transition(terminal, event));
            }
        }
    }

    @Test
    void failedRetryBackToPending() {
        // FAILED 运维重试 → PENDING（重试 = 全新运行）
        assertEquals(TaskStatus.PENDING,
                TaskStateMachine.transition(TaskStatus.FAILED, TaskEventType.RETRY));
    }

    @Test
    void illegalTransitionsThrow() {
        // PENDING 不能直接成功/失败
        assertThrows(TaskStateTransitionException.class,
                () -> TaskStateMachine.transition(TaskStatus.PENDING, TaskEventType.SUCCEEDED));
        assertThrows(TaskStateTransitionException.class,
                () -> TaskStateMachine.transition(TaskStatus.PENDING, TaskEventType.FAILED));
        // RUNNING 不能重新出队（除租约过期）
        assertThrows(TaskStateTransitionException.class,
                () -> TaskStateMachine.transition(TaskStatus.RUNNING, TaskEventType.DISPATCHED));
        // 终态之间不能互转
        assertThrows(TaskStateTransitionException.class,
                () -> TaskStateMachine.transition(TaskStatus.FAILED, TaskEventType.SUCCEEDED));
    }

    @Test
    void suspendedHasNoTransitionsYet() {
        for (TaskEventType event : TaskEventType.values()) {
            assertFalse(TaskStateMachine.canTransition(TaskStatus.SUSPENDED, event),
                    "SUSPENDED must be inert until HITL lands: " + event);
        }
    }

    @Test
    void terminalCheck() {
        assertFalse(TaskStateMachine.isTerminal(TaskStatus.PENDING));
        assertFalse(TaskStateMachine.isTerminal(TaskStatus.RUNNING));
        assertFalse(TaskStateMachine.isTerminal(TaskStatus.SUSPENDED));
        assertTrue(TaskStateMachine.isTerminal(TaskStatus.SUCCEEDED));
        assertTrue(TaskStateMachine.isTerminal(TaskStatus.FAILED));
        assertTrue(TaskStateMachine.isTerminal(TaskStatus.CANCELLED));
    }
}
