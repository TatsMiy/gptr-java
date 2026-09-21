package com.gptr.common.engine;

import com.gptr.common.task.TaskStage;

import java.util.Map;

/**
 * 研究进程活动观察者（观测台/流式事件源，2026-09 立项）。
 *
 * <p>引擎在细粒度动作（图节点开始/结束、一次检索、逐节写作完成）发生时同步回调；
 * worker 侧实现把它写成 {@code ACTIVITY} 事件（append-only，经 API WS 实时推送）。
 * sink 可空/未设置 = 无观测（零开销路径）。
 */
public interface ActivitySink {

    /**
     * 记录一次活动。
     *
     * @param stage  所在任务阶段（如 RESEARCH/SEARCHING/WRITING）
     * @param kind   活动种类：node | search | section | stage
     * @param label  人读摘要（如 "search", "extract_learnings", "引言与问题界定"）
     * @param detail 结构化细节（JSON 友好；如 {depth, queries, results}）
     */
    void emit(TaskStage stage, String kind, String label, Map<String, Object> detail);
}
