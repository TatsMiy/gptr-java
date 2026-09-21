package com.gptr.common.task;

/**
 * 研究任务状态。
 *
 * <p>状态转换由 {@link TaskStateMachine} 统一校验，终态（SUCCEEDED / FAILED / CANCELLED）不可逆。
 */
public enum TaskStatus {

    /** 已入队，未消费 */
    PENDING,

    /** worker 已拾取，执行中 */
    RUNNING,

    /** 暂停（预留：HITL 人工介入，P1 使用） */
    SUSPENDED,

    /** 正常完成 */
    SUCCEEDED,

    /** 失败（重试/租约耗尽） */
    FAILED,

    /** 用户取消 */
    CANCELLED
}
