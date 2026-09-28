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
      "act.crawlHealth": "抓取体检：{line}",

      /* 爬虫页签：上半 = 本进程窗口（爬虫重启即清零），下半 = 本任务的抓取体检 */
      "crawl.globalHeading": "本进程窗口（全局）",
      "crawl.taskHeading": "本任务抓取体检",
      "crawl.window": "窗口 {s}s · 单进程 {n} worker",
      "crawl.total": "抓取共 {n} 行",
      "crawl.outcome": "失败构成（按原因）",
      "crawl.pageKind": "页型分布",
      "crawl.statusClass": "状态码类",
      "crawl.truncated": "截断 {n} 行",
      "crawl.latencyLabel": "耗时",
      "crawl.latency": "p50 {p50}ms · p95 {p95}ms（分位取最近 {sampled}/{total} 次）",
      "crawl.topDomains": "按域名 Top {n}",
      "crawl.otherDomains": "其余 {total} 行来自 {domains} 个域名（另有 {unreadable} 行没有可用主机名）",
      "crawl.search": "检索",
      "crawl.byRetriever": "按检索器",
      "crawl.resultsPerQuery": "每查询结果数",
      "crawl.nonSite": "本地拒绝（不该算站点失败）：{list}",
      "crawl.funnel": "漏斗 检索 {collected} → 取名 {picked} → 抓成 {returned} → 有效 {valid}",
      "crawl.citedUnread": "引用未读源 {unread}/{cited}（{pct}%）",
      "crawl.buckets": "静默丢失 {silent} · 登录墙 {blocked} · 干净成功 {clean} · 降级成功 {degraded} · 其他失败 {failed}",
      "crawl.failures": "失败样本（URL + 原因）",
      "crawl.failuresOmitted": "另有 {n} 条超出上限未列出",
      "crawl.noHealth": "该任务没有抓取体检事件：旧任务，或本次没走到抓取",
      "crawl.unreachable": "爬虫不可达：{msg}",
      "crawl.noData": "本窗口还没有任何抓取记录",

      /* 证据库 */
      "evd.count": "{n} 条结构化证据（extract 提炼：insight 为模型判断，quote 为来源原文）",
      "evd.noneForTask": "该任务无证据库（deep RESEARCH 未产出 evidenceBank / flat 流水线）",
      "evd.none": "（无证据）",
      "evd.fold": "展开原文（{n} 字）",
      "evd.unfold": "收起",

      /* 报告 */
      "rpt.notSucceeded": "任务尚未成功完成，报告在 SUCCEEDED 后可用。",
      "rpt.loaded": "text/markdown 原文（{n} 字符）",

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
      "act.crawlHealth": "Crawl health: {line}",

      /* Crawler tab: top half is the process window (resets when the crawler restarts),
         bottom half is this task's crawl health. */
      "crawl.globalHeading": "This process window (global)",
      "crawl.taskHeading": "Crawl health for this task",
      "crawl.window": "Window {s}s · single process, {n} worker",
      "crawl.total": "{n} scraped rows",
      "crawl.outcome": "Failure composition (by reason)",
      "crawl.pageKind": "Page kinds",
      "crawl.statusClass": "Status classes",
      "crawl.truncated": "{n} truncated rows",
      "crawl.latencyLabel": "Latency",
      "crawl.latency": "p50 {p50}ms · p95 {p95}ms (percentiles over the last {sampled}/{total} calls)",
      "crawl.topDomains": "Top {n} domains",
      "crawl.otherDomains": "{total} more rows from {domains} domains ({unreadable} rows had no usable host)",
      "crawl.search": "Search",
      "crawl.byRetriever": "By retriever",
      "crawl.resultsPerQuery": "Results per query",
      "crawl.nonSite": "Local refusals (not site failures): {list}",
      "crawl.funnel": "Funnel: collected {collected} → picked {picked} → returned {returned} → valid {valid}",
      "crawl.citedUnread": "Citations to never-fetched pages {unread}/{cited} ({pct}%)",
      "crawl.buckets": "silent drops {silent} · login walls {blocked} · clean {clean} · degraded {degraded} · other failures {failed}",
      "crawl.failures": "Failure samples (URL + reason)",
      "crawl.failuresOmitted": "{n} more beyond the cap are not listed",
      "crawl.noHealth": "No crawl-health event for this task: an older task, or it never reached scraping",
      "crawl.unreachable": "Crawler unreachable: {msg}",
      "crawl.noData": "No scrape has been recorded in this window yet",

      /* Evidence */
      "evd.count": "{n} structured evidence items (extracted: insight is the model's judgement, quote is the source's original text)",
      "evd.noneForTask": "No evidence bank for this task (deep RESEARCH produced none / flat pipeline)",
      "evd.none": "(no evidence)",
      "evd.fold": "Show full text ({n} chars)",
      "evd.unfold": "Collapse",

      /* Report */
      "rpt.notSucceeded": "Task has not succeeded yet; the report becomes available after SUCCEEDED.",
      "rpt.loaded": "Raw text/markdown ({n} chars)",

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
