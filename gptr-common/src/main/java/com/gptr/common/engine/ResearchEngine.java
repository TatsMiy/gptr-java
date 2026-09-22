package com.gptr.common.engine;

import com.gptr.common.task.TaskStage;

import java.util.List;

/**
 * 研究引擎接口（底座持有控制循环，引擎实现阶段迭代）。
 *
 * <p>实现方：gptr-engine 的 {@code ResearchEngineImpl}（GPT-Researcher 的 Java 化流水线）；
 * 测试可换 mock 实现——实现本接口即可被底座驱动。
 */
public interface ResearchEngine {

    /** 本次研究要执行的阶段列表（固定五阶段，可被实现覆盖）。 */
    List<TaskStage> stages();

    /** 执行一个阶段，返回该阶段的中间结果（写入 checkpoint）。 */
    StageResult runStage(TaskStage stage);

    /** 全部阶段完成后产出最终报告文本。 */
    String finalReport();

    /** 注入研究进程活动观察者（观测台/流式；可空=无观测）。 */
    default void setActivitySink(ActivitySink sink) {
        // 默认无观测（引擎实现按需 override）
    }
}
