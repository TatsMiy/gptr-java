/* gptr 观测台 —— 原生 JS，无依赖
 * 状态纪律：
 *   - 时间线唯一真相 = WS append-only（seq 幂等，断线重连全量回放）
 *   - 任务列表 / 统计卡 = 轮询（轻量聚合端点，非时间线）
 *   - 重快照（报告正文）= 按需拉取，绝不进 WS
 * 安全：所有动态文本经 textContent 注入，禁止 innerHTML 拼用户内容。
 */
"use strict";

const API = "/api/v1/tasks";

const { t } = window.I18N;
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "");

/* ---------------- 工具 ---------------- */
async function j(method, url, body) {
  const opt = { method, headers: {} };
  if (body !== undefined) {
    opt.headers["Content-Type"] = "application/json";
    opt.body = JSON.stringify(body);
  }
  const r = await fetch(url, opt);
  if (!r.ok) {
    let msg = esc(r.status);
    try { msg = await r.text(); } catch (_) { /* ignore */ }
    throw new Error(msg.slice(0, 400));
  }
  const ct = r.headers.get("content-type") || "";
  return ct.includes("json") ? r.json() : r.text();
}
// 日期格式跟随界面语言：只有 locale 跟着变，格式规则本身不变
const locale = () => (I18N.lang === "zh" ? "zh-CN" : "en");
const fmtTime = (iso) => iso
  ? new Date(iso).toLocaleTimeString(locale(), { hour12: false }) : "–";
const fmtDate = (iso) => iso
  ? new Date(iso).toLocaleString(locale(), { hour12: false }) : "–";
const trunc = (s, n) => (s && s.length > n ? s.slice(0, n) + "…" : s);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
};

/* ---------------- 统计卡 ---------------- */
let statsCache = null;   // 切语言时从缓存重渲，不重新发请求

async function loadStats() {
  try {
    const s = await j("GET", API + "/stats");
    statsCache = s;
    renderStats();
  } catch (e) { /* 服务未起时静默，卡保持旧值 */ }
}

function renderStats() {
  const s = statsCache;
  if (!s) return;
  $("statTotal").textContent = s.tasks24h;
  $("statCost").textContent = Number(s.cost24hUsd).toFixed(4);
  $("statRunning").textContent = s.byStatus.RUNNING ?? 0;
  $("statOk").textContent = s.byStatus.SUCCEEDED ?? 0;
  $("statBad").textContent = t("stat.badValue", {
    failed: s.byStatus.FAILED ?? 0, cancelled: s.byStatus.CANCELLED ?? 0 });
  $("statSince").textContent = fmtDate(s.since);
}

/* ---------------- 任务列表 ---------------- */
const listState = { status: "", offset: 0, pageSize: 30, hasMore: false, lastTasks: [] };

async function loadTasks() {
  const p = new URLSearchParams({ offset: listState.offset, limit: listState.pageSize });
  if (listState.status) p.set("status", listState.status);
  try {
    const tasks = await j("GET", API + "?" + p.toString());
    listState.hasMore = tasks.length >= listState.pageSize;
    listState.lastTasks = tasks;
    renderTasks(tasks);
  } catch (e) {
    $("taskEmpty").hidden = false;
    $("taskEmpty").textContent = t("msg.loadFailed", { msg: e.message });
  }
}

function renderTasks(tasks) {
  const body = $("taskBody");
  body.replaceChildren();
  $("taskEmpty").hidden = tasks.length > 0;
  for (const t of tasks) {
    const tr = el("tr");
    const badge = el("span", "status-badge " + t.status, t.status);
    badge.title = t.errorCode ? "error: " + t.errorCode : t.status;
    const mode = el("span", "mode-badge " + t.mode, t.mode === "deep_research" ? "deep" : "flat");
    const q = el("td", "q", trunc(t.query, 60));
    q.title = t.query;
    q.onclick = () => openDrawer(t.id);
    const view = el("button", "btn", t("btn.view"));
    view.onclick = () => openDrawer(t.id);
    const cell = (node) => { const c = el("td"); c.appendChild(node); return c; };
    const actions = cell(view);
    tr.append(
      cell(badge),
      cell(mode),
      q,
      el("td", "", String(t.attempt)),
      el("td", "", String(t.stepsUsed)),
      el("td", "", Number(t.costSpentUsd).toFixed(4)),
      el("td", "", fmtDate(t.createdAt)),
      actions);
    body.appendChild(tr);
  }
  $("pagePrev").disabled = listState.offset === 0;
  $("pageNext").disabled = !listState.hasMore;
}

/* ---------------- 详情抽屉 ---------------- */
const drawerState = {
  taskId: null,
  task: null,
  mode: null,             // 加载到任务后由 mode 决定阶段条子集
  ws: null,
  reconnect: 0,
  seqs: new Set(),        // 已渲染事件 seq（幂等去重）
  rows: [],               // 事件 DOM 行（截断用）
  events: [],             // 事件对象缓存（切语言时重渲时间线用）
  evidence: null,         // 最近一次证据视图 {total, notes}
  cap: 500,
  tab: "timeline",
  stage: {},              // stage 名 -> 状态 active/done/fail
  reportLoaded: false,
};

function openDrawer(id) {
  closeWs();
  evOpen.clear(); // 证据展开记忆不跨任务（idx 各任务自 0 起，防串）
  drawerState.taskId = id;
  drawerState.task = null;
  drawerState.mode = null;
  drawerState.seqs = new Set();
  drawerState.rows = [];
  drawerState.events = [];
  drawerState.evidence = null;
  drawerState.stage = {};
  drawerState.reportLoaded = false;
  drawerState.reportChars = null;
  // 修复（实测）：切换任务时若上次停在"证据库"页签，旧任务的证据 DOM 会残留显示在新任务
  // 标题下（q04 的 570 条出现在 Code agents 上）。打开任务一律回到时间线页签并清空旧证据。
  drawerState.tab = "timeline";
  switchTab("timeline");
  $("drawer").hidden = false;
  $("scrim").hidden = false;
  $("timeline").replaceChildren(el("div", "tl-cap", t("tl.connecting")));
  $("dId").textContent = id;
  renderStageStrip();
  connectWs(id);
  loadTask(id);
}

async function loadTask(id) {
  try {
    const t = await j("GET", API + "/" + id);
    drawerState.task = t;
    drawerState.mode = t.mode;
    renderStageStrip();
    renderDrawerHead(t);
    renderDrawerMeta(t);
  } catch (e) {
    $("dMeta").textContent = t("msg.loadFailed", { msg: e.message });
  }
}

function renderDrawerHead(t) {
  $("dStatus").textContent = t.status;
  $("dStatus").className = "status-badge " + t.status;
  const mode = $("dMode");
  mode.textContent = t.mode === "deep_research" ? "deep_research" : "flat";
  mode.className = "mode-badge " + t.mode;
  $("dQuery").textContent = t.query;
  const running = t.status === "RUNNING" || t.status === "PENDING";
  $("dCancel").disabled = !running;
  $("dRetry").disabled = t.status !== "FAILED";
}

function renderDrawerMeta(t) {
  const m = $("dMeta");
  m.replaceChildren();
  const kv = (k, v) => {
    const s = el("span");
    s.append(el("b", "", k + " "), document.createTextNode(esc(v)));
    return s;
  };
  m.append(
    kv(t("meta.mode"), t.mode), kv(t("meta.attempts"), t.attempt + "/" + t.maxAttempts),
    kv(t("meta.steps"), t.stepsUsed), kv(t("meta.cost"), "$" + Number(t.costSpentUsd).toFixed(4)),
    kv(t("meta.created"), fmtDate(t.createdAt)),
    kv(t("meta.started"), t.startedAt ? fmtDate(t.startedAt) : "–"),
    kv(t("meta.finished"), t.finishedAt ? fmtDate(t.finishedAt) : "–"));
  const err = $("dError");
  if (t.status === "FAILED" && (t.errorCode || t.errorDetail)) {
    err.hidden = false;
    err.textContent = (t.errorCode ? "[" + t.errorCode + "] " : "") +
      (t.errorDetail ? trunc(t.errorDetail, 400) : t("meta.noErrorDetail"));
  } else {
    err.hidden = true;
  }
}

/* ---- 阶段条：按任务模式渲染真实会经过的阶段 ----
 * deep 的搜索/抓取/提炼/反思都在 RESEARCH 图内，SEARCHING/SCRAPING/SUMMARIZING
 * 是 flat 流水线专用——固定并集会让每个 deep 任务挂着 3 个永不点亮的灰阶段。 */
const MODE_STAGES = {
  deep_research: ["PLANNING", "RESEARCH", "WRITING"],
  flat: ["PLANNING", "SEARCHING", "SCRAPING", "SUMMARIZING", "WRITING"],
};

function renderStageStrip() {
  const strip = $("stageStrip");
  strip.replaceChildren();
  const stages = MODE_STAGES[drawerState.mode] || [];
  if (!stages.length) {
    strip.appendChild(el("span", "stage-pill", t("stage.loading")));
    return;
  }
  for (const s of stages) {
    const st = drawerState.stage[s] || "";
    const pill = el("span", "stage-pill" + (st ? " " + st : ""), s);
    strip.appendChild(pill);
  }
}

function noteStage(type, stage) {
  if (type === "STAGE_STARTED" && stage) drawerState.stage[stage] = "active";
  if (type === "STAGE_COMPLETED" && stage) drawerState.stage[stage] = "done";
  renderStageStrip();
}

/* ---- WS 时间线（唯一真相） ---- */
function connectWs(id) {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  let ws;
  try {
    ws = new WebSocket(proto + "//" + location.host + "/ws/tasks/" + id);
  } catch (_) {
    scheduleReconnect(id);
    return;
  }
  drawerState.ws = ws;
  ws.onmessage = (msg) => {
    drawerState.reconnect = 0;
    let ev;
    try { ev = JSON.parse(msg.data); } catch (_) { return; }
    appendEvent(ev);
  };
  ws.onclose = () => { scheduleReconnect(id); };
  ws.onerror = () => { try { ws.close(); } catch (_) { /* ignore */ } };
}

function scheduleReconnect(id) {
  if (drawerState.taskId !== id) return; // 抽屉已切换/关闭
  const delay = Math.min(1000 * Math.pow(2, drawerState.reconnect++), 15000);
  setTimeout(() => {
    if (drawerState.taskId === id) {
      // 回放语义：清空后由 WS 订阅推送全量（seq 幂等重建）
      drawerState.seqs = new Set();
      drawerState.rows = [];
      drawerState.events = [];   // 回放语义：清空缓存，随后由 WS 重建
      $("timeline").replaceChildren(el("div", "tl-cap", t("tl.reconnecting")));
      connectWs(id);
    }
  }, delay);
}

function closeWs() {
  if (drawerState.ws) {
    try { drawerState.ws.close(); } catch (_) { /* ignore */ }
    drawerState.ws = null;
  }
}

function appendEvent(ev) {
  if (drawerState.seqs.has(ev.seq)) return; // 幂等去重
  drawerState.seqs.add(ev.seq);
  noteStage(ev.type, ev.stage);
  drawerState.events.push(ev);
  const row = buildEventRow(ev);
  const tl = $("timeline");
  if (tl.querySelector(".tl-cap")) tl.replaceChildren();
  tl.appendChild(row);
  drawerState.rows.push(row);
  if (drawerState.rows.length > drawerState.cap) {
    const drop = drawerState.rows.splice(0, drawerState.rows.length - drawerState.cap);
    for (const d of drop) d.remove();
  }
  tl.scrollTop = tl.scrollHeight;
  // 完成后刷新任务头部（成本/状态/按钮态）
  if (ev.type !== "ACTIVITY") loadTask(drawerState.taskId);
  if (drawerState.tab === "report" && ev.type === "SUCCEEDED") loadReport(true);
}

/* 切语言重渲时间线：从缓存的事件对象重建（不发请求、不丢已收事件） */
function rerenderTimeline() {
  const tl = $("timeline");
  if (!drawerState.events.length) return;   // 还在占位文案阶段，交给 apply() 处理
  tl.replaceChildren();
  drawerState.rows = [];
  for (const ev of drawerState.events) {
    const row = buildEventRow(ev);
    tl.appendChild(row);
    drawerState.rows.push(row);
  }
  tl.scrollTop = tl.scrollHeight;
}

/* 事件类型 → 展示 */
function buildEventRow(ev) {
  const row = el("div", "tl-row");
  const icon = el("div", "tl-icon", "•");
  const main = el("div", "tl-main");
  const label = el("div", "tl-label");
  const sub = el("div", "tl-sub");

  const T = ev.type;
  const plain = (t, ic, cls, subText) => {
    icon.textContent = ic;
    if (cls) row.classList.add(cls);
    label.textContent = t;
    if (subText) sub.textContent = subText;
  };

  if (T === "STAGE_STARTED") { icon.textContent = "▶"; row.classList.add("stage-start"); label.textContent = t("ev.stageStarted", { stage: ev.stage }); }
  else if (T === "STAGE_COMPLETED") { icon.textContent = "✅"; row.classList.add("stage-end"); label.textContent = t("ev.stageCompleted", { stage: ev.stage }); parseStagePayload(ev.payload, sub); }
  else if (T === "CREATED") plain(t("ev.created"), "📥", "");
  else if (T === "DISPATCHED") plain(t("ev.dispatched"), "🚀", "");
  else if (T === "RETRY") plain(t("ev.retry"), "🔁", "ev-fail");
  else if (T === "BUDGET_EXCEEDED") plain(t("ev.budgetExceeded"), "💰", "ev-fail");
  else if (T === "SUCCEEDED") plain(t("ev.succeeded"), "🎉", "ev-succ");
  else if (T === "FAILED") plain(t("ev.failed"), "❌", "ev-fail");
  else if (T === "CANCELLED") plain(t("ev.cancelled"), "🛑", "");
  else if (T === "WEBHOOK_SENT") plain(t("ev.webhookSent"), "🔔", "");
  else if (T === "LEASE_EXPIRED") plain(t("ev.leaseExpired"), "⏳", "ev-fail");
  else if (T === "ACTIVITY") renderActivity(ev.payload, icon, row, label, sub);
  else plain(T, "•", "");

  const meta = el("div", "tl-meta", "#" + ev.seq + " " + fmtTime(ev.createdAt));
  main.append(label, sub);
  row.append(icon, main, meta);
  return row;
}

/* ACTIVITY payload：{kind, label, detail{...}}（telemetry-only，安全解析） */
function renderActivity(payloadRaw, icon, row, label, sub) {
  let p = {};
  try { p = typeof payloadRaw === "string" ? JSON.parse(payloadRaw) : (payloadRaw || {}); } catch (_) { /* ignore */ }
  const kind = p.kind || "?";
  const d = p.detail || {};
  const name = esc(p.label || "");
  if (kind === "node") {
    const phase = d.phase === "start" ? "▶ " : (d.phase === "fail" ? "✗ " : "✓ ");
    icon.textContent = "⚙️";
    row.classList.add("ev-node");
    if (d.phase === "fail") row.classList.add("ev-fail");
    label.textContent = t("act.node", { phase, name });
    const bits = [];
    if (d.depth !== undefined) bits.push("depth=" + d.depth);
    if (d.learnings !== undefined) bits.push("learnings=" + d.learnings);
    if (d.bank !== undefined) bits.push("bank=" + d.bank);
    if (d.queries !== undefined) bits.push("queries=" + d.queries);
    if (d.elapsedMs !== undefined) bits.push(t("act.elapsed", { s: (d.elapsedMs / 1000).toFixed(1) }));
    if (d.chain) bits.push("chain=" + d.chain);
    sub.textContent = bits.join(" · ");
  } else if (kind === "search") {
    icon.textContent = "🔎";
    row.classList.add("ev-search");
    label.textContent = name || t("act.search");
    const bits = [];
    if (d.chain) bits.push(t("act.chain", { chain: d.chain }));
    if (d.queries !== undefined) bits.push(t("act.queries", { n: d.queries }));
    if (d.results !== undefined) bits.push(t("act.results", { n: d.results }));
    if (d.retrieverCfg) bits.push("retriever=" + d.retrieverCfg);
    sub.textContent = bits.join(" · ");
  } else if (kind === "section") {
    icon.textContent = "📝";
    row.classList.add("ev-section");
    label.textContent = t("act.sectionDone", { name: name || t("act.noTitle") });
    const bits = [];
    if (d.chars !== undefined) bits.push(t("act.chars", { k: (d.chars / 1000).toFixed(1) }));
    if (d.unauthorized) bits.push(t("act.unauthorized", { n: d.unauthorized }));
    if (d.retried) bits.push(t("act.retried", { n: d.retried }));
    if (d.index !== undefined) bits.push(t("act.sectionIndex", { n: d.index + 1 }));
    sub.textContent = bits.join(" · ");
  } else {
    icon.textContent = "•";
    label.textContent = name
      ? t("act.activityNamed", { kind, name }) : t("act.activity", { kind });
    sub.textContent = JSON.stringify(d);
  }
}

/* 阶段完成 payload 摘要（扁平 + deep） */
function parseStagePayload(payloadRaw, sub) {
  let p = {};
  try { p = typeof payloadRaw === "string" ? JSON.parse(payloadRaw) : (payloadRaw || {}); } catch (_) { return; }
  const bits = [];
  for (const k of ["queries", "sources", "sections", "learnings", "citedUrls", "reportChars", "depthReached", "evidenceNotes", "followUpQuestions", "writingMode", "clarifyApplied"]) {
    if (p[k] !== undefined) bits.push(k + "=" + p[k]);
  }
  if (bits.length) sub.textContent = bits.join(" · ");
}

/* ---- 报告页签 ---- */
function switchTab(tab) {
  drawerState.tab = tab;
  for (const b of document.querySelectorAll(".tab")) b.classList.toggle("active", b.dataset.tab === tab);
  $("tabTimeline").hidden = tab !== "timeline";
  $("tabEvidence").hidden = tab !== "evidence";
  $("tabReport").hidden = tab !== "report";
  if (tab === "evidence") loadEvidence();
  if (tab === "report") loadReport(false);
}

/* ---- 证据库页签（OBS-2.5：重快照按需拉取，不进 WS） ---- */
const evOpen = new Set(); // 已展开全文的 note idx（会话内记忆）

async function loadEvidence() {
  const id = drawerState.taskId;
  if (!id) return;
  const list = $("evList");
  const hint = $("evHint");
  hint.textContent = t("stage.loading");
  try {
    const v = await j("GET", API + "/" + id + "/evidence");
    drawerState.evidence = v;                     // 切语言时从这里重渲
    hint.textContent = v.total > 0
      ? t("evd.count", { n: v.total })
      : t("evd.noneForTask");
    renderEvidence(list, v.notes);
  } catch (e) {
    list.replaceChildren();
    hint.textContent = String(e.message);          // 服务端消息原样显示，不翻译
  }
}

/* 切语言重渲证据库：hint 重新取词，卡片重渲（不发请求） */
function rerenderEvidence() {
  const v = drawerState.evidence;
  if (!v) return;
  $("evHint").textContent = v.total > 0
    ? t("evd.count", { n: v.total })
    : t("evd.noneForTask");
  renderEvidence($("evList"), v.notes);
}

function renderEvidence(list, notes) {
  list.replaceChildren();
  if (!notes.length) {
    list.appendChild(el("div", "ev-none", t("evd.none")));
    return;
  }
  for (const n of notes) {
    const card = el("div", "ev-card");
    const head = el("div", "ev-head");
    head.appendChild(el("span", "ev-idx", "#" + n.idx));
    head.appendChild(el("span", "ev-q", trunc(n.queryText || "", 120)));
    head.appendChild(el("span", "ev-badge",
      "depth " + n.depth + " · round " + n.round + " · q" + n.queryIdx));

    const insight = el("div", "ev-insight");
    if (n.insight) {
      const b = el("b", "", "insight  ");
      insight.append(b, document.createTextNode(n.insight));
    }

    const quoteBox = el("div");
    if (n.quote) {
      const QUOTE_FOLD = 300;
      const folded = n.quote.length > QUOTE_FOLD && !evOpen.has(n.idx);
      const shown = folded ? n.quote.slice(0, QUOTE_FOLD) + "…" : n.quote;
      const q = el("div", "ev-quote", shown);
      quoteBox.appendChild(q);
      if (n.quote.length > QUOTE_FOLD) {
        const btn = el("button", "ev-fold",
          folded ? t("evd.fold", { n: n.quote.length }) : t("evd.unfold"));
        btn.onclick = () => {
          if (folded) evOpen.add(n.idx); else evOpen.delete(n.idx);
          renderEvidence(list, notes); // 整表重渲（含记忆展开态）
        };
        quoteBox.appendChild(btn);
      }
    }

    const urlBox = el("div", "ev-url");
    const u = n.sourceUrl || "";
    if (/^https?:\/\//i.test(u)) {
      const a = document.createElement("a");
      a.href = u;               // 仅 http(s) 渲染为外链（防 javascript: 注入）
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      a.textContent = u;
      urlBox.appendChild(a);
    } else if (u) {
      urlBox.appendChild(el("span", "plain", u));
    }

    card.append(head, insight, quoteBox, urlBox);
    list.appendChild(card);
  }
}

async function loadReport(force) {
  const id = drawerState.taskId;
  if (!id) return;
  if (!force && drawerState.reportLoaded) return;
  drawerState.reportLoaded = true;
  const body = $("reportBody");
  const hint = $("reportHint");
  if (drawerState.task && drawerState.task.status !== "SUCCEEDED") {
    body.textContent = "";
    hint.textContent = t("rpt.notSucceeded");
    return;
  }
  hint.textContent = t("stage.loading");
  try {
    const txt = await j("GET", API + "/" + id + "/report");
    body.textContent = txt;
    drawerState.reportChars = txt.length;
    hint.textContent = t("rpt.loaded", { n: txt.length });
  } catch (e) {
    body.textContent = "";
    hint.textContent = String(e.message);
  }
}

/* ---- 操作：取消 / 重试 ---- */
async function doCancel() {
  try {
    await j("POST", API + "/" + drawerState.taskId + "/cancel");
    loadTask(drawerState.taskId);
  } catch (e) { alert(t("msg.cancelFailed", { msg: e.message })); }
}
async function doRetry() {
  try {
    await j("POST", API + "/" + drawerState.taskId + "/retry");
    // 重试 = 全新运行：清时间线由 WS 回放（事件链从头）
    drawerState.seqs = new Set();
    drawerState.rows = [];
    drawerState.events = [];
    $("timeline").replaceChildren(el("div", "tl-cap", t("tl.retryQueued")));
    loadTask(drawerState.taskId);
  } catch (e) { alert(t("msg.retryFailed", { msg: e.message })); }
}

/* ---------------- 提交弹窗（含 OBS-2.5 Fork 蓝图） ---------------- */
const submitState = { blueprint: null }; // 非空 = 以某任务 config 为底，提交时 merge

const numOr = (v, d) => (typeof v === "number" && v > 0 ? v : d);

function openSubmitDialog(tpl) {
  const f = $("submitForm");
  if (tpl) {
    submitState.blueprint = tpl.config || {};
    $("submitDialogTitle").textContent = t("form.forkTitle");
    $("fQuery").value = tpl.query || "";
    const c = submitState.blueprint;
    setRadioMode(c.mode === "deep_research" ? "deep_research" : "flat");
    $("fDepth").value = numOr(c.depth, 2);
    $("fBreadth").value = numOr(c.breadth, 3);
    $("fLanguage").value = c.language || "中文";
    $("fSectionWriting").checked = c.sectionWriting !== false;
    const b = c.budgets || {};
    $("fSteps").value = numOr(b.steps, 100);
    $("fTime").value = numOr(b.timeSeconds, 1800);
    $("fCost").value = numOr(b.costUsd, 10);
    $("fRetriever").value = c.retriever || "";
  } else {
    submitState.blueprint = null;
    $("submitDialogTitle").textContent = t("form.title");
    f.reset(); // 回 HTML 默认（radio=deep、sectionWriting 勾选、数字默认值）
  }
  $("submitDialog").showModal();
  $("fQuery").focus();
}

function setRadioMode(mode) {
  const r = document.querySelector('input[name="mode"][value="' + mode + '"]');
  if (r) r.checked = true;
  applyModeControls();
}

/* flat 模式禁用深研专属控件（depth/breadth/sectionWriting），消除"模式残留"观感 */
function applyModeControls() {
  const deep = (document.querySelector('input[name="mode"]:checked') || {}).value === "deep_research";
  for (const id of ["fDepth", "fBreadth"]) {
    const el = $(id);
    if (el) el.disabled = !deep;
  }
  const sw = $("fSectionWriting");
  if (sw) sw.disabled = !deep;
}

function bindModeToggle() {
  for (const r of document.querySelectorAll('input[name="mode"]')) {
    r.addEventListener("change", applyModeControls);
  }
  applyModeControls();
}

async function submitResearch(ev) {
  ev.preventDefault();
  const query = $("fQuery").value.trim();
  if (!query) return;
  const mode = document.querySelector('input[name="mode"]:checked').value;
  const blueprint = submitState.blueprint || {};
  // Fork 语义：以源 config 为底，UI 显式键覆盖；源 config 未知键原样保留
  const cfg = Object.assign({}, blueprint);
  cfg.mode = mode;
  if (mode === "deep_research") {
    cfg.depth = Number($("fDepth").value) || 2;
    cfg.breadth = Number($("fBreadth").value) || 3;
  } else {
    delete cfg.depth;
    delete cfg.breadth;
    delete cfg.sectionWriting;
    delete cfg.planReflect;
    delete cfg.perQueryExtract;
    delete cfg.clarifyQuestions;
    delete cfg.followUpDriven;
  }
  cfg.language = $("fLanguage").value;
  if (mode === "deep_research") {
    cfg.sectionWriting = $("fSectionWriting").checked;
  } else {
    delete cfg.sectionWriting;
  }
  cfg.budgets = Object.assign({}, blueprint.budgets || {}, {
    steps: Number($("fSteps").value) || 100,
    timeSeconds: Number($("fTime").value) || 1800,
    costUsd: Number($("fCost").value) || 10,
  });
  const retriever = $("fRetriever").value.trim();
  if (retriever) cfg.retriever = retriever;
  else delete cfg.retriever;

  const btn = $("fSubmit");
  btn.disabled = true;
  btn.textContent = t("btn.submitting");
  try {
    const r = await j("POST", API, {
      query,
      config: JSON.stringify(cfg),
      clientKey: crypto.randomUUID ? crypto.randomUUID() : undefined,
    });
    submitState.blueprint = null;
    $("submitDialog").close();
    $("fQuery").value = "";
    listState.offset = 0;           // 新任务在最前：回第一页并刷新
    await loadTasks();
    openDrawer(r.taskId);           // 自动打开新任务抽屉，WS 直播其事件
  } catch (e) {
    alert(t("msg.submitFailed", { msg: e.message }));
  } finally {
    btn.disabled = false;
    btn.textContent = t("btn.doSubmit");
  }
}

/* OBS-2.5 Fork：源任务 → template 蓝图 → 预填弹窗（原任务不受影响） */
async function openForkDialog() {
  const id = drawerState.taskId;
  if (!id) return;
  try {
    const tpl = await j("GET", API + "/" + id + "/template");
    openSubmitDialog(tpl);
  } catch (e) {
    alert(t("msg.templateFailed", { msg: e.message }));
  }
}

/* ---------------- 全局配置（OBS-3：保存后重启 worker 生效，无热加载） ---------------- */
const CONFIG_API = "/api/v1/config";
let cfgRows = []; // 最近一次 GET 的配置视图

async function openConfigDialog() {
  await loadConfig();
  $("configDialog").showModal();
}

async function loadConfig() {
  try {
    cfgRows = await j("GET", CONFIG_API);
    renderConfig();
  } catch (e) {
    cfgRows = [];
    $("configList").replaceChildren(el("div", "ev-none", t("msg.cfgLoadFailed", { msg: e.message })));
  }
}

function renderConfig() {
  const list = $("configList");
  list.replaceChildren();
  for (const v of cfgRows) {
    const row = el("div", "cfg-row");
    const left = el("div");
    left.appendChild(el("div", "cfg-key", v.key));
    // 服务端 description 是中文；前端按 key 映射本地文案，未命中则用服务端原文
    left.appendChild(el("div", "cfg-desc", I18N.configDescription(v) || v.description));

    const state = el("div", "cfg-state" + (v.set ? " set" : ""),
      v.set ? (v.secret ? t("cfg.set", { version: v.version })
        : t("cfg.overridden", { value: trunc(v.value, 40), version: v.version }))
        : t("cfg.notOverridden"));
    if (v.set && v.updatedAt) state.title = t("cfg.updatedAt", { time: fmtDate(v.updatedAt) });

    const editor = el("div", "cfg-editor");
    const input = document.createElement("input");
    input.type = v.secret ? "password" : "text";
    input.placeholder = v.secret
      ? (v.set ? t("cfg.phSecretSet") : t("cfg.phSecretNew"))
      : (v.set ? v.value : "");
    input.value = v.secret ? "" : (v.value || "");
    input.dataset.key = v.key;
    const save = el("button", "btn", t("btn.save"));
    save.onclick = () => saveConfigRow(v, input);
    const reset = el("button", "btn warn", t("btn.reset"));
    reset.disabled = !v.set;
    reset.onclick = () => resetConfigRow(v);
    editor.append(input, save, reset);

    row.append(left, editor, state);
    list.appendChild(row);
  }
}

async function saveConfigRow(view, input) {
  const value = input.value.trim();
  if (view.secret) {
    if (!value && view.set) { flashConfig(t("cfg.secretBlank")); return; }
    if (!value) { flashConfig(t("cfg.needKey")); return; }
  }
  try {
    const saved = await j("PUT", CONFIG_API + "/" + encodeURIComponent(view.key), { value });
    flashConfig(t("cfg.saved", { key: view.key, version: saved.version }));
    await loadConfig();
  } catch (e) {
    flashConfig(t("msg.saveFailed", { msg: e.message }), true);
  }
}

async function resetConfigRow(view) {
  try {
    await j("DELETE", CONFIG_API + "/" + encodeURIComponent(view.key));
    flashConfig(t("cfg.resetDone", { key: view.key }));
    await loadConfig();
  } catch (e) {
    flashConfig(t("msg.resetFailed", { msg: e.message }), true);
  }
}

let flashTimer = null;
function flashConfig(msg, isError) {
  let f = document.querySelector(".cfg-saved");
  if (!f) {
    f = el("div", "cfg-saved");
    $("configDialog").querySelector(".cfg-footer").prepend(f);
  }
  f.style.color = isError ? "var(--bad)" : "var(--ok)";
  f.textContent = msg;
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => { f.remove(); }, 6000);
}

/* ---------------- 轮询纪律（列表/统计；页面不可见或自动刷新关闭时暂停） ---------------- */
setInterval(() => {
  if (document.hidden || !$("autoRefresh").checked) return;
  loadStats();   // stats 端点自带 5s API 缓存，4s 轮询无压力
  loadTasks();
}, 4000);

/* ---------------- 事件绑定 ---------------- */
/* 切语言：从缓存重渲当前视图，**不调用任何 load*** ——
 * 否则一次切换触发 6 个 API 调用，还会冲掉滚动位置与证据展开态。 */
function rerenderAll() {
  renderStats();
  renderTasks(listState.lastTasks || []);
  if (drawerState.task) {
    renderDrawerHead(drawerState.task);
    renderDrawerMeta(drawerState.task);
  }
  renderStageStrip();
  rerenderTimeline();
  if (drawerState.evidence) rerenderEvidence();
  if (drawerState.reportChars !== null && drawerState.task) {
    $("reportHint").textContent = t("rpt.loaded", { n: drawerState.reportChars });
  }
  if (cfgRows.length) renderConfig();
  if (!submitState.blueprint) $("submitDialogTitle").textContent = t("form.title");
}

function bind() {
  $("langSelect").value = I18N.lang;
  $("langSelect").onchange = () => I18N.setLang($("langSelect").value);
  I18N.onLangChange(() => {
    $("langSelect").value = I18N.lang;
    rerenderAll();
  });
  $("submitBtn").onclick = () => openSubmitDialog(null);
  $("fCancel").onclick = () => $("submitDialog").close();
  $("submitForm").addEventListener("submit", submitResearch);
  bindModeToggle();
  $("dFork").onclick = openForkDialog;
  $("configBtn").onclick = openConfigDialog;
  $("cfgClose").onclick = () => $("configDialog").close();
  $("cfgDone").onclick = () => $("configDialog").close();
  $("cfgCopyCmd").onclick = () => {
    const cmd = $("cfgRestartCmd").textContent.trim();
    navigator.clipboard.writeText(cmd).then(
      () => flashConfig(t("cfg.copied")),
      () => flashConfig(t("cfg.copyFailed"), true));
  };
  $("statusFilter").onchange = () => {
    listState.status = $("statusFilter").value;
    listState.offset = 0;
    loadTasks();
  };
  $("reloadTasks").onclick = loadTasks;
  $("pagePrev").onclick = () => { listState.offset = Math.max(0, listState.offset - listState.pageSize); loadTasks(); };
  $("pageNext").onclick = () => { if (listState.hasMore) { listState.offset += listState.pageSize; loadTasks(); } };
  $("dClose").onclick = closeDrawer;
  $("scrim").onclick = closeDrawer;
  $("dCancel").onclick = doCancel;
  $("dRetry").onclick = doRetry;
  $("reportReload").onclick = () => { drawerState.reportLoaded = false; loadReport(true); };
  $("evReload").onclick = loadEvidence;
  for (const b of document.querySelectorAll(".tab")) {
    b.onclick = () => switchTab(b.dataset.tab);
  }
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !$("drawer").hidden && !$("submitDialog").open) closeDrawer();
  });
}

function closeDrawer() {
  closeWs();
  drawerState.taskId = null;
  $("drawer").hidden = true;
  $("scrim").hidden = true;
}

/* ---------------- 启动 ---------------- */
bind();
loadStats();
loadTasks();
