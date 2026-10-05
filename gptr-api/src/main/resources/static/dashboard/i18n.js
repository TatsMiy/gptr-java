/* gptr 观测台 —— 界面文案层（零依赖，中/英）
 *
 * 设计：静态文案走 data-i18n 属性，动态文案走 t(key, params)，词典与业务逻辑分离。
 *
 * 三种绑定方式（由 apply() 统一处理）：
 *   data-i18n="key"                -> el.textContent = t(key)
 *   data-i18n-html="key"           -> el.innerHTML  = t(key)     仅用于文案含内联标签处
 *   data-i18n-attr="title:k1,x:k2" -> 逗号分隔的「属性名:key」逐项 setAttribute
 *
 * 纪律：
 *   - **「值」不进字典**：提交表单里 #fLanguage 的 option（中文/English）是发给引擎的协议字符串，
 *     不是 UI 文案 —— 语言切换不得改动其 value 与显示文本。
 *   - **「数据」不翻**：任务 query、报告正文、证据 insight/quote、服务端返回的 e.message，一律原样。
 *   - **缺 key 不静默**：t(未知 key) 返回 key 本身并 console.warn；不抛异常、不置空。
 *     元素上失效的 data-i18n 保留**原地文本**（不是清空）。
 *   - 占位符用 {名}；参数缺失时原样保留 {名}，便于一眼看出漏传。
 *
 * 词典两侧 key 必须**完全相同** —— 由 DashboardI18nTest 机械守卫。
 */
"use strict";

(function () {
  const DICT = {
    zh: {
      /* 顶栏 */
      "app.title": "gptr 观测台",
      "app.subtitle": "deep research 任务控制与事件时间线",
      "app.autoRefresh": "自动刷新",
      "app.langLabel": "界面语言",
      "lang.zh": "中文",
      "lang.en": "English",

      /* 通用按钮 */
      "btn.config": "⚙ 配置",
      "btn.config.title": "全局运行配置（保存后重启 worker 生效）",
      "btn.submit": "＋ 提交新研究",
      "btn.reload": "↻ 刷新",
      "btn.reloadShort": "↻ 重新加载",
      "btn.older": "‹ 更早",
      "btn.newer": "更新 ›",
      "btn.view": "查看",
      "btn.cancel": "取消",
      "btn.cancelTask": "取消",
      "btn.retry": "重试",
      "btn.fork": "📋 复制为新任务",
      "btn.fork.title": "以本任务为蓝图新建独立任务（可改参数后提交）",
      "btn.doSubmit": "提交",
      "btn.submitting": "提交中…",
      "btn.save": "保存",
      "btn.reset": "恢复默认",
      "btn.copyCmd": "复制命令",
      "btn.done": "完成",

      /* 统计卡 */
      "stat.tasks24h": "24h 任务",
      "stat.cost24h": "24h 花费",
      "stat.running": "运行中",
      "stat.ok24h": "24h 成功",
      "stat.bad24h": "24h 失败",
      "stat.since": "窗口起点",
      "stat.badValue": "{failed} / 取消 {cancelled}",

      /* 任务列表 */
      "task.heading": "任务",
      "task.filterAll": "全部状态",
      "task.empty": "暂无任务",
      "task.thStatus": "状态",
      "task.thMode": "模式",
      "task.thQuery": "研究问题",
      "task.thAttempts": "尝试",
      "task.thSteps": "步数",
      "task.thCost": "花费 $",
      "task.thCreated": "创建时间",

      /* 抽屉外壳 */
      "tab.timeline": "时间线",
      "tab.evidence": "证据库",
      "tab.report": "报告",
      "tab.crawl": "爬虫",

      /* 抽屉元信息 */
      "meta.mode": "模式",
      "meta.attempts": "尝试",
      "meta.steps": "步数",
      "meta.cost": "花费",
      "meta.created": "创建",
      "meta.started": "开始",
      "meta.finished": "完成",
      "meta.noErrorDetail": "（无详细错误）",

      /* 时间线 */
      "tl.connecting": "连接实时事件流…",
      "tl.reconnecting": "断线重连中…",
      "tl.retryQueued": "重试已入队，等待事件回放…",
      "stage.loading": "加载中…",
      "ev.stageStarted": "阶段开始：{stage}",
      "ev.stageCompleted": "阶段完成：{stage}",
      "ev.created": "任务入队",
      "ev.dispatched": "worker 拾取",
      "ev.retry": "重试（attempt+1）",
      "ev.budgetExceeded": "预算超限，优雅终止",
      "ev.succeeded": "研究完成",
      "ev.failed": "任务失败",
      "ev.cancelled": "已取消",
      "ev.webhookSent": "webhook 已投递",
      "ev.leaseExpired": "租约过期",

      /* ACTIVITY 明细 */
      "act.node": "{phase}节点 {name}",
      "act.elapsed": "耗时 {s}s",
      "act.search": "检索",
      "act.chain": "链 {chain}",
      "act.queries": "查询×{n}",
      "act.results": "结果×{n}",
      "act.sectionDone": "章节完成：{name}",
      "act.noTitle": "(无标题)",
      "act.chars": "{k}k 字",
      "act.unauthorized": "未授权引用×{n}",
      "act.retried": "重写×{n}",
      "act.sectionIndex": "第 {n} 节",
      "act.activity": "活动 {kind}",
      "act.activityNamed": "活动 {kind}：{name}",
      "act.crawlHealth": "抓取流程：{line}",
      "act.depthLayer": "第 {n} 层",
      "act.learnings": "事实×{n}",
      "act.bank": "证据库 {n} 条",
      "act.searchSource": "检索源 {name}",

      /* 图节点 id → 人话（8 个，与引擎的 DeepResearchGraph 常量一一对应）。
         ⚠️ 只在**表现层**映射：事件 payload 里的节点 id 是 API 契约，一个字段都不改
         （`DashboardNodeLabelsTest` 拿引擎常量与这里的 key 对账，防"新加节点界面显示原 id"悄悄发生）。 */
      "node.research_plan": "制定研究计划",
      "node.generate_queries": "拆解研究维度、生成检索词",
      "node.search": "全网检索",
      "node.scrape": "抓取网页正文",
      "node.curate_sources": "筛选来源质量",
      "node.extract_learnings": "从网页里提炼事实",
      "node.plan_reflect": "检查覆盖、找缺口",
      "node.follow_up_queries": "生成下一轮追问",

      /* 阶段摘要的字段名 → 人话（STAGE_COMPLETED 那一行）。
         渲染成"名字 值"，不再印 `queries=4` 这种字段名。 */
      "stage.queries": "检索词",
      "stage.sources": "来源",
      "stage.sections": "章节",
      "stage.learnings": "事实",
      "stage.citedUrls": "引用",
      "stage.reportChars": "报告字数",
      "stage.depthReached": "层数",
      "stage.evidenceNotes": "证据",
      "stage.followUpQuestions": "追问",
      "stage.writingMode": "写作方式",
      "stage.clarifyApplied": "问题澄清",
      "stageVal.section": "逐节写作",
      "stageVal.single": "单遍写作",
      "stageVal.yes": "已应用",
      "stageVal.no": "未应用",

      /* 爬虫页签：上半 = 本进程窗口（爬虫重启即清零），下半 = 本任务的抓取体检。
         文案纪律：**界面说人话，字段名留给 API** —— 读者不需要知道后端把它叫 picked，
         只需要知道"从检索结果里挑了几个去抓"。把内部术语原样搬上界面，等于把调测日志投影给读者。 */
      "crawl.globalHeading": "本进程窗口（全局）",
      "crawl.taskHeading": "本任务抓取体检",
      "crawl.window": "统计窗口 {s} 秒 · 单进程（{n} 个 worker）",
      "crawl.total": "共抓取 {n} 次",
      "crawl.outcome": "失败原因分布",
      "crawl.pageKind": "页面类型分布",
      "crawl.statusClass": "HTTP 状态分布",
      "crawl.truncated": "正文被截断 {n} 次",
      "crawl.latencyLabel": "耗时",
      "crawl.latency": "中位 {p50} 毫秒 · 95 分位 {p95} 毫秒（按最近 {sampled}/{total} 次计算）",
      "crawl.topDomains": "抓取最多的 {n} 个站点",
      "crawl.otherDomains": "其余 {total} 次来自 {domains} 个站点（另有 {unreadable} 次没有可用主机名）",
      "crawl.search": "检索",
      "crawl.byRetriever": "按检索源",
      "crawl.resultsPerQuery": "每次检索返回条数",
      "crawl.nonSite": "本地拒绝（不算站点的问题）：{list}",
      "crawl.nonSiteNone": "本轮没有",
      "crawl.funnel": "抓取流程：检索到 {collected} 个来源 → 挑出 {picked} 个去抓 → 抓回 {returned} 页 → 其中可用 {valid} 页",
      "crawl.citedUnread": "报告引用了 {cited} 个来源，其中 {unread} 个（{pct}%）始终没抓到正文",
      "crawl.buckets": "没有原因就消失 {silent} 次 · 撞上登录墙/付费墙 {blocked} 次 · 干净抓到 {clean} 次 · 抓到但有保留 {degraded} 次 · 其他失败 {failed} 次",
      "crawl.failures": "失败明细（网址 + 原因）",
      "crawl.failuresOmitted": "另有 {n} 条超出上限未列出",
      "crawl.noHealth": "该任务没有抓取体检事件：旧任务，或本次没走到抓取",
      "crawl.unreachable": "爬虫服务不可达：{msg}",
      "crawl.noData": "本次统计窗口内还没有抓取记录",

      /* 爬虫返回的取值域译名（码 → 人话）。
         ⚠️ 取值域**由爬虫侧拥有**（`crawler/pycrawler/scraper/outcome.py` 的 REASONS/PAGE_KINDS），
         这里只是**镜像**：译不到的码**照原样显示**（不空白、不报错）⇒ 爬虫新增一个原因时界面退化为显示码，
         而不是坏掉。镜像与主人是否一致由测试钉住（`DashboardCodeLabelsTest`）。
         `2xx`–`5xx` 不译：HTTP 状态类是通用说法。 */
      "code.ok": "成功",
      "code.http_4xx": "站点返回 4xx",
      "code.http_5xx": "站点返回 5xx",
      "code.timeout": "超时",
      "code.network_error": "连不上",
      "code.too_large": "文件过大（本地放弃）",
      "code.no_session": "缺会话/代理配置（本地放弃）",
      "code.empty_content": "抽不出正文",
      "code.too_short": "正文太短",
      "code.block_challenge": "被反爬拦截",
      "code.word_list": "疑似词表挑战页",
      "code.pdf_unresolved": "PDF 打不开",
      "code.unsafe_url": "地址不安全（本地拒绝）",
      "code.exception": "处理时异常",
      "code.unknown": "原因未知",
      "code.empty": "没返回结果",
      "code.error": "调用出错",
      "code.network": "网络层失败",
      "code.none": "没有状态码",
      "code.article": "正文页",
      "code.listing": "列表页",
      "code.login_or_wall": "登录墙/付费墙",
      "code.download_or_resource": "下载/资源页",
      "code.error_page": "错误页",
      "code.other": "其他",
      /* ⚠️ 同名不同义：`unknown` 在"失败原因"里是"没有交代原因"，在"页型"里是"判不出是什么页"。
         用同一张扁平表会把后者说错（实测："页面类型分布 原因未知×100"）。
         ⇒ 页型侧单独留一个词条，按词表取用。 */
      "pagekind.unknown": "未能判定页型",

      /* 证据库 */
      "evd.count": "{n} 条结构化证据（extract 提炼：insight 为模型判断，quote 为来源原文）",
      "evd.noneForTask": "该任务无证据库（deep RESEARCH 未产出 evidenceBank / flat 流水线）",
      "evd.none": "（无证据）",
      "evd.fold": "展开原文（{n} 字）",
      "evd.unfold": "收起",

      /* 报告 */
      "rpt.notSucceeded": "任务尚未成功完成，报告在 SUCCEEDED 后可用。",
      "rpt.loaded": "text/markdown 原文（{n} 字符）",
      "rpt.viewRendered": "渲染",
      "rpt.viewRendered.title": "渲染后的排版视图（默认）",
      "rpt.viewRaw": "原文",
      "rpt.viewRaw.title": "报告原文（markdown 源码，所见即文件）",
      "rpt.rendered": "已渲染（原文 {n} 字符）",
      "rpt.fallback": "渲染不可用，已回退显示原文（原文 {n} 字符）",

      /* 提交弹窗 */
      "form.title": "提交新研究",
      "form.forkTitle": "📋 复制为新任务（可改参数，提交后为独立任务）",
      "form.queryLabel": "研究问题 *",
      "form.queryPlaceholder": "例如：DeepSeek-R1 的推理模式与常规模式在哪些任务上差异最大？",
      "form.modeLabel": "模式",
      "form.modeDeep": "deep_research <em>递归搜索 + 逐节写作（默认）</em>",
      "form.modeFlat": "flat <em>单轮流水线（快、浅）</em>",
      "form.advancedSummary": "高级参数（默认 = 生产默认）",
      "form.depth": "深度 depth",
      "form.breadth": "广度 breadth",
      "form.language": "语言",
      "form.sectionWriting": "逐节写作 sectionWriting",
      "form.steps": "步骤上限",
      "form.timeLimit": "时长上限(秒)",
      "form.costLimit": "成本上限($)",
      "form.retriever": "retriever（可下拉选，留空 = 默认链）",

      /* 配置弹窗 */
      "cfg.title": "全局运行配置",
      "cfg.note": "作用于新任务（worker 启动时读取覆盖内置默认）。<b>保存后需重启 worker 生效</b>；不热替换、不影响在跑任务。secret 只写不读：页面永不回显明文。",
      "cfg.restartHint": "重启命令（在 gptr-java 目录执行，等当前任务跑完或租约回收后自然接手）：",
      "cfg.set": "已设置 · v{version}",
      "cfg.overridden": "已覆盖：{value} · v{version}",
      "cfg.notOverridden": "未覆盖（内置默认）",
      "cfg.updatedAt": "更新于 {time}",
      "cfg.phSecretSet": "已设置——输入新值以覆盖",
      "cfg.phSecretNew": "输入 API Key",
      "cfg.saved": "{key} 已保存（v{version}）——重启 worker 后生效",
      "cfg.resetDone": "{key} 已恢复内置默认——重启 worker 后生效",
      "cfg.secretBlank": "secret 留空 = 不改动（已设置）",
      "cfg.needKey": "请输入 API Key",
      "cfg.copied": "重启命令已复制",
      "cfg.copyFailed": "复制失败，请手动选择复制",
      "cfg.llm.provider": "LLM 提供方：openai（OpenAI 兼容，默认 DeepSeek）| mock（配置驱动测试）",
      "cfg.llm.model": "LLM 模型名（如 deepseek-chat / deepseek-reasoner）",
      "cfg.llm.baseUrl": "OpenAI 兼容 API 端点（如 https://api.deepseek.com）",
      "cfg.llm.apiKey": "LLM API Key（secret：不回显明文，仅显示已设置/未设置）",
      "cfg.search.chain": "检索降级链（逗号分隔：duckduckgo / python；或 name:mode mock）",
      "cfg.crawler.baseUrl": "Python 爬虫服务地址（/search /scrape）",
      "cfg.retrieverKey": "检索器 {name} 的 API Key（secret；未填则回落环境变量）",

      /* 通用消息 */
      "msg.loadFailed": "加载失败：{msg}",
      "msg.cancelFailed": "取消失败：{msg}",
      "msg.retryFailed": "重试失败：{msg}",
      "msg.submitFailed": "提交失败：{msg}",
      "msg.templateFailed": "加载任务模板失败：{msg}",
      "msg.cfgLoadFailed": "加载失败：{msg}",
      "msg.saveFailed": "保存失败：{msg}",
      "msg.resetFailed": "失败：{msg}"
    },

    en: {
      /* Top bar */
      "app.title": "gptr Dashboard",
      "app.subtitle": "Deep research task control & event timeline",
      "app.autoRefresh": "Auto-refresh",
      "app.langLabel": "UI language",
      "lang.zh": "Chinese",
      "lang.en": "English",

      /* Buttons */
      "btn.config": "⚙ Config",
      "btn.config.title": "Global runtime config (takes effect after a worker restart)",
      "btn.submit": "＋ New research",
      "btn.reload": "↻ Refresh",
      "btn.reloadShort": "↻ Reload",
      "btn.older": "‹ Older",
      "btn.newer": "Newer ›",
      "btn.view": "View",
      "btn.cancel": "Cancel",
      "btn.cancelTask": "Cancel",
      "btn.retry": "Retry",
      "btn.fork": "📋 Duplicate as new task",
      "btn.fork.title": "Create a new independent task from this one's blueprint (editable before submitting)",
      "btn.doSubmit": "Submit",
      "btn.submitting": "Submitting…",
      "btn.save": "Save",
      "btn.reset": "Reset to default",
      "btn.copyCmd": "Copy command",
      "btn.done": "Done",

      /* Stats */
      "stat.tasks24h": "Tasks (24h)",
      "stat.cost24h": "Cost (24h)",
      "stat.running": "Running",
      "stat.ok24h": "Succeeded (24h)",
      "stat.bad24h": "Failed (24h)",
      "stat.since": "Window start",
      "stat.badValue": "{failed} / {cancelled} cancelled",

      /* Task list */
      "task.heading": "Tasks",
      "task.filterAll": "All statuses",
      "task.empty": "No tasks",
      "task.thStatus": "Status",
      "task.thMode": "Mode",
      "task.thQuery": "Question",
      "task.thAttempts": "Attempts",
      "task.thSteps": "Steps",
      "task.thCost": "Cost $",
      "task.thCreated": "Created",

      /* Drawer shell */
      "tab.timeline": "Timeline",
      "tab.evidence": "Evidence",
      "tab.report": "Report",
      "tab.crawl": "Crawler",

      /* Drawer meta */
      "meta.mode": "Mode",
      "meta.attempts": "Attempts",
      "meta.steps": "Steps",
      "meta.cost": "Cost",
      "meta.created": "Created",
      "meta.started": "Started",
      "meta.finished": "Finished",
      "meta.noErrorDetail": "(no error detail)",

      /* Timeline */
      "tl.connecting": "Connecting to live event stream…",
      "tl.reconnecting": "Reconnecting…",
      "tl.retryQueued": "Retry queued, awaiting event replay…",
      "stage.loading": "Loading…",
      "ev.stageStarted": "Stage started: {stage}",
      "ev.stageCompleted": "Stage completed: {stage}",
      "ev.created": "Task enqueued",
      "ev.dispatched": "Picked up by worker",
      "ev.retry": "Retry (attempt+1)",
      "ev.budgetExceeded": "Budget exceeded, graceful stop",
      "ev.succeeded": "Research completed",
      "ev.failed": "Task failed",
      "ev.cancelled": "Cancelled",
      "ev.webhookSent": "Webhook delivered",
      "ev.leaseExpired": "Lease expired",

      /* ACTIVITY detail */
      "act.node": "{phase}Node {name}",
      "act.elapsed": "{s}s elapsed",
      "act.search": "Search",
      "act.chain": "chain {chain}",
      "act.queries": "{n} queries",
      "act.results": "{n} results",
      "act.sectionDone": "Section done: {name}",
      "act.noTitle": "(untitled)",
      "act.chars": "{k}k chars",
      "act.unauthorized": "{n} unauthorized citations",
      "act.retried": "{n} rewrites",
      "act.sectionIndex": "section {n}",
      "act.activity": "Activity {kind}",
      "act.activityNamed": "Activity {kind}: {name}",
      "act.crawlHealth": "Crawl flow: {line}",
      "act.depthLayer": "layer {n}",
      "act.learnings": "{n} facts",
      "act.bank": "evidence bank: {n}",
      "act.searchSource": "source {name}",

      /* Graph node ids → plain language (8, one per engine constant in DeepResearchGraph).
         ⚠️ Presentation layer only: the node id inside the event payload is an API contract
         and is left untouched (`DashboardNodeLabelsTest` reconciles these keys against the
         engine constants, so a newly added node cannot silently show up as a raw id). */
      "node.research_plan": "Planning the research",
      "node.generate_queries": "Breaking the question into searches",
      "node.search": "Searching the web",
      "node.scrape": "Fetching page text",
      "node.curate_sources": "Filtering source quality",
      "node.extract_learnings": "Extracting facts from pages",
      "node.plan_reflect": "Checking coverage, finding gaps",
      "node.follow_up_queries": "Generating follow-up questions",

      /* Stage-summary field names → plain language (the STAGE_COMPLETED row).
         Rendered as "name value" instead of printing `queries=4`. */
      "stage.queries": "searches",
      "stage.sources": "sources",
      "stage.sections": "sections",
      "stage.learnings": "facts",
      "stage.citedUrls": "citations",
      "stage.reportChars": "report chars",
      "stage.depthReached": "layers",
      "stage.evidenceNotes": "evidence items",
      "stage.followUpQuestions": "follow-ups",
      "stage.writingMode": "writing mode",
      "stage.clarifyApplied": "clarification",
      "stageVal.section": "per-section",
      "stageVal.single": "single pass",
      "stageVal.yes": "applied",
      "stageVal.no": "not applied",

      /* Crawler tab: top half is the process window (resets when the crawler restarts),
         bottom half is this task's crawl health.
         Wording rule: the interface speaks plainly, field names stay in the API. A reader
         does not need to know the backend calls it `picked`, only how many search hits were
         chosen to fetch. */
      "crawl.globalHeading": "This process window (global)",
      "crawl.taskHeading": "Crawl health for this task",
      "crawl.window": "Window {s}s · single process ({n} worker)",
      "crawl.total": "{n} fetches recorded",
      "crawl.outcome": "Why fetches failed",
      "crawl.pageKind": "Page types",
      "crawl.statusClass": "HTTP status",
      "crawl.truncated": "{n} pages were truncated",
      "crawl.latencyLabel": "Latency",
      "crawl.latency": "median {p50} ms · 95th percentile {p95} ms (over the last {sampled}/{total} calls)",
      "crawl.topDomains": "Busiest {n} sites",
      "crawl.otherDomains": "{total} more fetches from {domains} sites ({unreadable} had no usable host name)",
      "crawl.search": "Search",
      "crawl.byRetriever": "By search source",
      "crawl.resultsPerQuery": "Results per search",
      "crawl.nonSite": "Refused locally (not the site's problem): {list}",
      "crawl.nonSiteNone": "none this window",
      "crawl.funnel": "Fetch flow: {collected} sources found → {picked} chosen to fetch → {returned} pages came back → {valid} usable",
      "crawl.citedUnread": "The report cites {cited} sources, {unread} of them ({pct}%) were never fetched",
      "crawl.buckets": "{silent} vanished without a reason · {blocked} hit a login/paywall · {clean} fetched cleanly · {degraded} fetched with caveats · {failed} other failures",
      "crawl.failures": "Failure details (URL + reason)",
      "crawl.failuresOmitted": "{n} more beyond the cap are not listed",
      "crawl.noHealth": "No crawl-health event for this task: an older task, or it never reached scraping",
      "crawl.unreachable": "Crawler service unreachable: {msg}",
      "crawl.noData": "No fetches recorded in this window yet",

      /* Human names for the values the crawler returns.
         ⚠️ The vocabulary is **owned by the crawler side** (`crawler/pycrawler/scraper/outcome.py`,
         REASONS/PAGE_KINDS); this is only a **mirror**. A code with no entry here is displayed
         as-is (never blank, never an error), so a new reason on the crawler side degrades to
         showing the code instead of breaking. A test pins mirror against owner
         (`DashboardCodeLabelsTest`). `2xx`–`5xx` are left alone: HTTP status classes are
         already common wording. */
      "code.ok": "ok",
      "code.http_4xx": "site returned 4xx",
      "code.http_5xx": "site returned 5xx",
      "code.timeout": "timed out",
      "code.network_error": "could not connect",
      "code.too_large": "too large (gave up locally)",
      "code.no_session": "no session/proxy config (gave up locally)",
      "code.empty_content": "no main content extracted",
      "code.too_short": "text too short",
      "code.block_challenge": "blocked by anti-bot",
      "code.word_list": "looks like a challenge page",
      "code.pdf_unresolved": "PDF could not be opened",
      "code.unsafe_url": "unsafe address (refused locally)",
      "code.exception": "error while processing",
      "code.unknown": "reason unknown",
      "code.empty": "no results",
      "code.error": "call failed",
      "code.network": "network-layer failure",
      "code.none": "no status code",
      "code.article": "article",
      "code.listing": "listing",
      "code.login_or_wall": "login wall / paywall",
      "code.download_or_resource": "download / resource",
      "code.error_page": "error page",
      "code.other": "other",
      /* ⚠️ Same code, different meaning: `unknown` as a failure reason means "no reason was
         recorded", while as a page kind it means "could not tell what this page is".
         A single flat table would mislabel the latter, so page kinds get their own entry. */
      "pagekind.unknown": "page type unknown",

      /* Evidence */
      "evd.count": "{n} structured evidence items (extracted: insight is the model's judgement, quote is the source's original text)",
      "evd.noneForTask": "No evidence bank for this task (deep RESEARCH produced none / flat pipeline)",
      "evd.none": "(no evidence)",
      "evd.fold": "Show full text ({n} chars)",
      "evd.unfold": "Collapse",

      /* Report */
      "rpt.notSucceeded": "Task has not succeeded yet; the report becomes available after SUCCEEDED.",
      "rpt.loaded": "Raw text/markdown ({n} chars)",
      "rpt.viewRendered": "Rendered",
      "rpt.viewRendered.title": "Formatted view (default)",
      "rpt.viewRaw": "Raw",
      "rpt.viewRaw.title": "Raw markdown source, exactly as stored",
      "rpt.rendered": "Rendered (raw source: {n} chars)",
      "rpt.fallback": "Rendering unavailable, showing raw text instead (raw source: {n} chars)",

      /* Submit dialog */
      "form.title": "New research",
      "form.forkTitle": "📋 Duplicate as new task (editable; submitted as an independent task)",
      "form.queryLabel": "Research question *",
      "form.queryPlaceholder": "e.g. On which tasks do DeepSeek-R1's reasoning and standard modes differ most?",
      "form.modeLabel": "Mode",
      "form.modeDeep": "deep_research <em>Recursive search + section-wise writing (default)</em>",
      "form.modeFlat": "flat <em>Single-pass pipeline (fast, shallow)</em>",
      "form.advancedSummary": "Advanced (defaults = production)",
      "form.depth": "Depth",
      "form.breadth": "Breadth",
      "form.language": "Language",
      "form.sectionWriting": "Section-wise writing",
      "form.steps": "Step limit",
      "form.timeLimit": "Time limit (s)",
      "form.costLimit": "Cost limit ($)",
      "form.retriever": "Retriever (pick from list or type freely; blank = default chain)",

      /* Config dialog */
      "cfg.title": "Global runtime config",
      "cfg.note": "Applies to new tasks (read at worker startup, overriding built-in defaults). <b>Takes effect after a worker restart</b>; no hot reload, does not affect running tasks. Secrets are write-only: the page never echoes plaintext.",
      "cfg.restartHint": "Restart command (run in the gptr-java directory; it picks up after the current task finishes or its lease is reclaimed):",
      "cfg.set": "Set · v{version}",
      "cfg.overridden": "Overridden: {value} · v{version}",
      "cfg.notOverridden": "Not overridden (built-in default)",
      "cfg.updatedAt": "Updated {time}",
      "cfg.phSecretSet": "Already set — enter a new value to override",
      "cfg.phSecretNew": "Enter API key",
      "cfg.saved": "{key} saved (v{version}) — takes effect after a worker restart",
      "cfg.resetDone": "{key} reset to built-in default — takes effect after a worker restart",
      "cfg.secretBlank": "Secret left blank = unchanged (already set)",
      "cfg.needKey": "Please enter an API key",
      "cfg.copied": "Restart command copied",
      "cfg.copyFailed": "Copy failed; please select and copy manually",
      "cfg.llm.provider": "LLM provider: openai (OpenAI-compatible, DeepSeek by default) | mock (config-driven tests)",
      "cfg.llm.model": "LLM model name (e.g. deepseek-chat / deepseek-reasoner)",
      "cfg.llm.baseUrl": "OpenAI-compatible API endpoint (e.g. https://api.deepseek.com)",
      "cfg.llm.apiKey": "LLM API key (secret: never echoed, only shows set/unset)",
      "cfg.search.chain": "Retrieval fallback chain (comma-separated: duckduckgo / python; or name:mode mock)",
      "cfg.crawler.baseUrl": "Python crawler service base URL (/search /scrape)",
      "cfg.retrieverKey": "API key for retriever {name} (secret; falls back to an environment variable when unset)",

      /* Messages */
      "msg.loadFailed": "Load failed: {msg}",
      "msg.cancelFailed": "Cancel failed: {msg}",
      "msg.retryFailed": "Retry failed: {msg}",
      "msg.submitFailed": "Submit failed: {msg}",
      "msg.templateFailed": "Failed to load task template: {msg}",
      "msg.cfgLoadFailed": "Load failed: {msg}",
      "msg.saveFailed": "Save failed: {msg}",
      "msg.resetFailed": "Failed: {msg}"
    }
  };

  /* 服务端配置键 -> 本地字典键；未命中则用服务端 description 原文 */
  const CONFIG_KEY_MAP = {
    "llm.provider": "cfg.llm.provider",
    "llm.model": "cfg.llm.model",
    "llm.base-url": "cfg.llm.baseUrl",
    "llm.api-key": "cfg.llm.apiKey",
    "search.chain": "cfg.search.chain",
    "crawler.base-url": "cfg.crawler.baseUrl"
  };
  const RETRIEVER_KEY_RE = /^retriever\.([a-z0-9-]+)\.(api-key|cx-key)$/;

  const LS_KEY = "gptr.lang";
  const HTML_LANG = { zh: "zh-CN", en: "en" };
  let current = "zh";
  const listeners = [];

  function normalizeLang(v) {
    return v === "en" || v === "zh" ? v : null;
  }

  function detectLang() {
    let stored = null;
    try { stored = normalizeLang(localStorage.getItem(LS_KEY)); } catch (_) { /* 隐私模式 */ }
    if (stored) return stored;
    const nav = (navigator.language || "").toLowerCase();
    if (!nav) return "zh";                        // 缺失 ⇒ 兜底 zh
    return nav.indexOf("zh") === 0 ? "zh" : "en"; // zh* ⇒ zh，其余 ⇒ en
  }

  /** 取文案。缺 key 时返回 key 本身并 warn（不抛、不静默、不置空）。 */
  function t(key, params) {
    const table = DICT[current] || DICT.zh;
    let s = table[key];
    if (s === undefined) {
      console.warn("[i18n] missing key: " + key + " (" + current + ")");
      return key;
    }
    if (params) {
      s = s.replace(/\{(\w+)\}/g, (m, name) =>
        Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : m);
    }
    return s;
  }

  /** 把绑定属性套用到 root（默认 document）下的全部元素。 */
  function apply(root) {
    const scope = root || document;
    for (const el of scope.querySelectorAll("[data-i18n]")) {
      const key = el.getAttribute("data-i18n");
      const table = DICT[current] || DICT.zh;
      if (table[key] !== undefined) el.textContent = t(key);   // 失效 key 保留原地文本
    }
    for (const el of scope.querySelectorAll("[data-i18n-html]")) {
      const key = el.getAttribute("data-i18n-html");
      const table = DICT[current] || DICT.zh;
      if (table[key] !== undefined) el.innerHTML = t(key);
    }
    for (const el of scope.querySelectorAll("[data-i18n-attr]")) {
      for (const pair of el.getAttribute("data-i18n-attr").split(",")) {
        const i = pair.indexOf(":");
        if (i < 0) continue;
        const attr = pair.slice(0, i).trim();
        const key = pair.slice(i + 1).trim();
        const table = DICT[current] || DICT.zh;
        if (attr && table[key] !== undefined) el.setAttribute(attr, t(key));
      }
    }
  }

  function setLang(lang) {
    const next = normalizeLang(lang) || "zh";
    current = next;
    try { localStorage.setItem(LS_KEY, next); } catch (_) { /* 降级为内存态 */ }
    document.documentElement.lang = HTML_LANG[next] || next;
    apply(document);
    for (const fn of listeners) {
      try { fn(next); } catch (e) { console.error("[i18n] lang listener failed", e); }
    }
  }

  function onLangChange(fn) { listeners.push(fn); }

  /** 服务端给的配置描述 -> 当前语言文案（未命中时返回 undefined，调用方 fallback）。 */
  function configDescription(view) {
    const mapped = CONFIG_KEY_MAP[view.key];
    if (mapped) return t(mapped);
    const m = RETRIEVER_KEY_RE.exec(view.key || "");
    if (m) return t("cfg.retrieverKey", { name: m[1] });
    return undefined;
  }

  current = detectLang();

  window.I18N = { LANGS: ["zh", "en"], t, apply, setLang, onLangChange, configDescription,
                  get lang() { return current; } };
  document.documentElement.lang = HTML_LANG[current] || current;
  apply(document);
})();
