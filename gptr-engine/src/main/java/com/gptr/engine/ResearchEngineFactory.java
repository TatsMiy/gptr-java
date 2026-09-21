package com.gptr.engine;

import com.gptr.common.engine.ResearchEngine;
import com.gptr.common.task.ResearchTask;
import com.gptr.engine.context.ContextManager;
import com.gptr.engine.epoc.DistillGate;
import com.gptr.engine.epoc.PostgresCheckpointSaver;
import jakarta.annotation.PostConstruct;
import com.gptr.engine.plan.SubQueryPlanner;
import com.gptr.engine.search.Searcher;
import com.gptr.engine.write.ReportWriter;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.SearchClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 研究引擎工厂：创建 {@link ResearchEngineImpl}（注入经弹性层包装的外部客户端）。
 *
 * <p>checkpoint saver 可空（无 DB/测试场景用内存图 checkpoint）；scraperClient 可空
 * （无 Python 爬虫服务的环境跳过抓取，SCRAPING 记 note）；worker 装配时注入
 * Postgres 实现支撑图内续跑。
 *
 * <p>{@code gptr.engine.allow-mock-config}：生产默认 false——任务 config 里的
 * {@code mock.*} 测试旋钮一律不生效；集成测试显式置 true。
 */
@Component
@RequiredArgsConstructor
public class ResearchEngineFactory {

    private final SearchClient searchClient;
    private final LlmClient llmClient;
    private final SubQueryPlanner planner;
    private final Searcher searcher;

    @Nullable
    private final ScraperClient scraperClient;

    private final ContextManager contextManager;

    @Nullable
    private final PostgresCheckpointSaver checkpointSaver;

    @Value("${gptr.engine.allow-mock-config:false}")
    private boolean allowMockConfig;

    /** 蒸馏并发闸容量（<b>应用级</b>配置）。
     *  闸是**进程内共享**的，故容量必须来自与它同生命周期的配置；任务级 config 里的
     *  {@code distillConcurrency} 自 2026-09-14 起不再影响闸容量。 */
    @Value("${gptr.engine.distill-concurrency:3}")
    private int distillConcurrency;

    /** 进程内共享的蒸馏闸：{@link PostConstruct} 时按上面的容量建**一次**，此后不再变更
     *  （消除"整体替换字段"这一竞态来源）。 */
    private DistillGate distillGate;

    @PostConstruct
    void initDistillGate() {
        this.distillGate = new DistillGate(distillConcurrency);
    }

    public ResearchEngine create(ResearchTask task) {
        // J3：研报语言可配（config language：zh/中文 → 中文，en/English → English）
        EngineConfig cfg = new EngineConfig(task.getConfig());
        return new ResearchEngineImpl(task, new ResearchEngineImpl.EngineDeps(planner, searcher,
                llmClient, searchClient, new ReportWriter(llmClient, cfg.language), scraperClient,
                contextManager, checkpointSaver), allowMockConfig, distillGate);
    }
}
