package com.gptr.common.task;

/**
 * 任务状态迁移事件（append-only 事件日志 {@code task_events.type} 的取值）。
 */
public enum TaskEventType {

    /** 任务创建入队 */
    CREATED,

    /** worker 出队拾取 */
    DISPATCHED,

    /** worker 租约过期，回队重拾 */
    LEASE_EXPIRED,

    /** 阶段开始 */
    STAGE_STARTED,

    /** 阶段完成（写 checkpoint） */
    STAGE_COMPLETED,

    /** 可重试错误，attempt+1 */
    RETRY,

    /** 预算耗尽，优雅终止 */
    BUDGET_EXCEEDED,

    /** 正常完成 */
    SUCCEEDED,

    /** 失败（重试耗尽） */
    FAILED,

    /** 用户取消 */
    CANCELLED,

    /** webhook 投递 */
    WEBHOOK_SENT,

    /**
     * 研究进程活动（观测台/流式）：payload = JSON 对象
     * {"kind": "node|search|section", "label": "...", "detail": {...}}
     * kind=node：图/阶段节点开始或结束（detail.node, detail.phase=start|end, depth/learnings 等）；
     * kind=search：一次检索调用（label="searchAll"，detail.chain/retrieverCfg/queries/results）；
     * kind=section：逐节写作完成一节（label=节标题≤80 字符，detail.index/chars/unauthorized/retried）。
     */
    ACTIVITY
}
