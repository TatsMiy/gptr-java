# gptr-java

**中文** | [English](README.en.md)

面向**长程研究任务**的 deep research 服务（Java 21）：在 Postgres 任务队列底座
（原子出队 + 租约 + 预算）上运行 LangGraph4j 递归研究图，配 Web 观测台与可审计评测器。

> **来源**：本项目是 [gpt-researcher](https://github.com/assafelovic/gpt-researcher)
> （Apache-2.0）的 **Java 重写** —— 架构与 prompt 资产**派生自**该项目；`crawler/`
> 子目录由其**提取**并精简，携带其自有 `LICENSE`/`NOTICE`。文献借鉴与**有意偏离**
> 逐项列在 [§来源、借鉴与偏离](#来源借鉴与偏离)，完整归属见 [NOTICE](NOTICE)。

**技术栈**：Java 21 · Spring Boot 3.5 · LangGraph4j · Postgres · Flyway · MinIO ·
Resilience4j · vanilla JS 观测台

## 特性

**研究能力**

- **多轮递归，而不是一问一答**：先澄清问题，再按 `breadth × decay` 逐层检索；每层结束把
  "已覆盖什么、还缺什么" 写进研究计划，下一层的查询由这份计划生成。
- **先大纲、再逐节写作**：每节单独查证据、单独写，并注入已写好的前文以避免重复。
- **可审计的证据库**：每条结论都带**逐字原文**与来源 URL，以 JSON 入库 —— 事后可以逐条复核。

**可信度**

- **只允许引用真实来源**：模型可用的 URL 限定在"已检索且已提炼"的白名单内，
  引用越界会被核验拦下。对照实测：引用一致性 **1.00**、矛盾陈述 **0**。
- **参考文献由程序生成**：正文引用编号为 `[n]`，文末来源表由代码生成，不靠模型自行排列。
- **判官先自检再打分**：评测器先注入已知答案句测判官，判官可信之后才采信它的评分。

**工程**

- **任务底座**：Postgres 队列（原子出队）+ 租约续租守护（防脑裂）+ 步骤 / 时长 / 成本
  三重预算 + 优雅重试。
- **单页观测台**（`/dashboard/`）：实时事件时间线、任务与成本统计、**报告阅读（markdown 渲染 / 原文两视图）**、
  证据库浏览、配置面板、Fork 为蓝图；另有「爬虫」页签 —— 上半是本进程窗口的抓取计数
  （失败构成 / 按域名 Top-10 / 耗时 p50,p95 / 页型与状态码类 / 截断 / 检索每查询结果数），
  下半是该任务的抓取体检（检索→取名→抓成→有效 漏斗、静默丢失、**引用未读源比例**、失败样本）。
  前端**无框架、无构建链、无网络依赖**（报告渲染用仓内 vendored 的 `marked` + `DOMPurify`，见 `NOTICE`）。
- **测试与门禁**：engine **248** 单测 · crawler **81** 单测（`python -m unittest discover -s tests`）·
  benchmark **45** 单测 · 集成 **14 类 / 22 用例**；
  可读性门禁 13 项判据随 `mvn test` 自动执行。

## 快速开始

前置：JDK 21、Maven 3.9+、Docker（Compose）。

```bash
# 1) 本地基础设施（Postgres + MinIO + 爬虫服务）
docker compose up -d --build

# 2) 配置模型（任选其一）
#    a) OpenAI 兼容端点（DeepSeek / OpenAI / 通义 / 智谱 / 本地 Ollama 均可，见 BUILD.md）
#       两个启动参数都别省：provider=openai（不传则仍是 mock，产出的报告是编的）；
#       search-chain=python（走仓库内 crawler 服务；不传会回落 Java 原生检索器）
export DEEPSEEK_API_KEY=sk-...        # Windows: $env:DEEPSEEK_API_KEY="sk-..."
mvn -pl gptr-worker spring-boot:run "-Dspring-boot.run.arguments=--gptr.clients.llm-provider=openai --gptr.clients.search-chain=python"
#    b) 零 key 体验：llm-provider=mock（默认值）—— 不配任何 key 即可跑通整条流水线

# 3) API + 观测台
mvn -pl gptr-api spring-boot:run
#    打开 http://localhost:8080/dashboard/ 提交任务并实时观察研究过程

# 4) 提交任务（或直接用页面）
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"query":"…你的研究问题…","config":"{\"mode\":\"deep_research\",\"breadth\":3,\"depth\":2,\"retriever\":\"bocha\"}"}'
```

构建、集成测试与常见问题见 [BUILD.md](BUILD.md)。

## 来源、借鉴与偏离

本项目研读并逐条判决了 **7 篇** 2025–2026 的 deep research 论文，下表是**实际落点**
—— 每条都标了是"已实施 / 有意偏离 / 未采纳"：

| 机制 | 出处 | 落点 |
|---|---|---|
| 三态判定协议（Supported / Contradictory / Inconclusive）+ 反证判据 | **[DeepFact](https://doi.org/10.18653/v1/2026.acl-long.1586)** | ✅ 已实施：D2 判定协议（含每句 evidence 输出与 verdict 白名单）|
| **micro-gold 判官自检**（注入已知答案句先测判官，再信它的分）| DeepFact | ✅ 已实施：30 条机器锚定探针（1:4 对抗注入）|
| KAE-lite 引用覆盖（KSR / KCR / KOR + 第四态 `abstain`）| **[DeepResearch Arena](https://doi.org/10.1609/aaai.v40i39.40620)** | ✅ 已实施：从"引用"出发查报告是否把关键事实写全 |
| 题集"禁止来源"字段 + 泄漏率上报（工具层屏蔽 URL）| **[DeepResearch Bench II](https://doi.org/10.48550/arXiv.2601.08536)** | ✅ 已实施：`blockedUrls` + 检索结果层过滤（比论文"建议但未落地"更彻底）|
| rubric 原子化 / 内容承载写法 + 自评闸门 | DeepResearch Bench II | 参考（用于题库入库流程）|
| 证据条目结构（`insight` 与 `quote` 分离）| **[WebWeaver](https://doi.org/10.48550/arXiv.2509.13312)** | ✅ 已实施，并补 canonical 规范化与授权集校验 |
| 逐节写作 + 节级引用闸门 | WebWeaver + **[FS-Researcher](https://doi.org/10.18653/v1/2026.acl-long.288)** | ✅ 已实施。⚠️ 两篇结论**相反**：WebWeaver 警告逐节写作导致 *content and style incoherence*、主张节间连续叙事；FS-Researcher 的消融则认为逐节更优。本项目早期只采纳了"上下文隔离"，2026-09-19 以**已写节注入**补回全局连贯（重复度 8.0 → 5.75，双跑一致）|
| 层间 plan 反思 / 中央研究状态 | **[Reflect-Evolve](https://doi.org/10.48550/arXiv.2601.20843)** | ✅ 已实施：`plan_reflect` 节点（层末写"已覆盖 vs 缺口"，下轮查询生成读它）|
| 显式 plan + 允许修订 | **[DeepPlanner](https://doi.org/10.18653/v1/2026.findings-acl.370)** | 哲学采纳（research plan 作为受管理状态；其 RL 训练管线不做）|
| 蒸馏 / 脱水选句 | WebWeaver | ⚠️ **有意偏离**：论文用 LLM 生成 query-relevant summary（有损，且**无 fidelity 审计**）；本项目改为「**只删不改 + Java 程序化保真前检**」—— 因为 `quote` 的语义价值恰恰是"逐字" |
| "写完即剪"（write-then-prune）| WebWeaver | ❌ **未采纳**：本项目不累积证据（每节按需取用），无物可剪 |
| 来源质量闸（curate）| **gpt-researcher** `SourceCurator` | ⚠️ 移植时**自加了 prompt 输入预算**（原版**没有**任何字符预算）。2026-09-20 实测：候选一多，该预算会把**已抓取的正文块整批丢掉**（note 62 → 34）⇒ 已翻回默认关 |

## 与 gpt-researcher 的关系与差异

关系见首屏与 [NOTICE](NOTICE)：`crawler/` 是**提取**（非重写），主链路是**重写**。
以下是可验证的结构性差异，不是形容词：

| 维度 | gpt-researcher | 本项目 |
|---|---|---|
| 运行时 | Python + asyncio，单进程 | Java 21 + Spring Boot；api / worker / crawler 三进程 |
| 任务底座 | 进程内状态，无队列 | Postgres 队列 + 租约 + 预算 + 重试 + `ownerToken` fence |
| 证据层 | 上下文为拼接字符串 | **结构化证据库**（`insight` / `quote` / `sourceUrl` 三分离，JSON 入库）|
| 引用 | prompt 内软约束 | **授权来源集**（只允许引用已检索且已提炼的 URL）+ 机械 References + canonical 规范化 |
| 质量评测 | `evals/`（模型质量） | **可审计评测器**：判官自检 + 双口径 + 三态幻觉反证 + A/B 双跑盲判 |
| 来源质量闸 | `SourceCurator`，默认关 | 同一机制，曾翻为默认开；2026-09-20 因实测会丢素材而**翻回默认关**（见上表）|

**同题对照**（两个实现各跑一次，六维盲判 + 双跑换序，只认一致结果）：

| 轮次 | 题数 | `overall` | 说明 |
|---|---|---|---|
| 2026-09-13 | 4 | **2 : 2** | 本项目在 depth / citation / honesty 三维 2:1 领先 |
| 2026-09-20 | 2 | **本项目 2 : 0** | 其中一道"观点类"题从原版的 5 维领先被翻盘 |

⚠️ **边界**：样本仅 2–4 题；第二轮的原版报告为 9-13 版（未重跑）；判官与被测同源
（均 DeepSeek）⇒ 数字只作方向性参考，不构成全面优劣结论。

## 检索与抓取（可插拔）

- 检索降级链配置 `gptr.clients.search-chain`（逗号分隔，顺序尝试）：
  - `python` — 仓库内 `crawler/` 服务（**20** 个搜索引擎适配器，推荐完整档）
  - `duckduckgo` — 零 key 直连（Java 原生）
  - `name:ok|transient|quota|permanent` — mock 源（测试 / 演示）
- 任务级检索器由任务 config 的 `retriever` 指定（如 `bocha`）；**建议显式指定** ——
  默认检索器在部分网络环境下不可用，不指定会表现为"所有子查询失败"
- 密钥类配置（各检索器 API key、模型、检索链）可在观测台「⚙ 配置」面板持久化
  （保存后重启 worker 生效，无热加载）

## 评测

评测器自带 micro-gold 判官自检；每轮输出双口径准确率（条件口径 + 全样本严格口径）、
幻觉率（保守下限 / 含不确定上限）、引用一致性与引用覆盖分，并支持 A/B 双跑盲判。

```bash
mvn -pl gptr-benchmark test       # 评测器自身单测；真实评测的主类与参数见 BUILD.md
```

## 目录结构

```
gptr-java/
├── docker-compose.yml        # Postgres + MinIO + crawler（./crawler 相对构建）
├── crawler/                  # 无状态 Python 爬虫服务（自 gpt-researcher 提取，Apache-2.0）
├── gptr-common/              # 领域模型 / 状态机 / JPA / Flyway / 任务服务 / 事件日志
├── gptr-integration/         # 外部客户端接口 / 弹性层（重试·熔断·降级）/ 报告存储 / UrlSecurity
├── gptr-engine/              # 研究引擎：LangGraph4j 深研图 / 逐节写作 / 证据库 / 引用核验
├── gptr-api/                 # REST + WebSocket + 观测台单页（/dashboard/）
├── gptr-worker/              # 队列消费者：出队 / 租约 / 续租守护 / 预算 / 存储 / webhook
├── gptr-benchmark/           # 评测器（准确率 / 幻觉率 / 引用 / 覆盖 + A/B + micro-gold）
└── BUILD.md / README.md      # 构建测试与使用指南
```

## 许可与致谢

Apache-2.0，见 [LICENSE](LICENSE)。归属与致谢：

**项目**

- **[gpt-researcher](https://github.com/assafelovic/gpt-researcher)**（Apache-2.0，
  Copyright (c) Assaf Elovic and contributors）—— 架构与 prompt 资产派生自该项目；
  `crawler/` 为其提取精简版，携带其自有 `LICENSE` / `NOTICE`

**论文**（逐篇的借鉴点与偏离见 [§来源、借鉴与偏离](#来源借鉴与偏离)）

- [DeepFact: Co-Evolving Benchmarks and Agents for Deep Research Factuality](https://doi.org/10.18653/v1/2026.acl-long.1586)（ACL 2026）—— 三态判定协议与 micro-gold 判官自检
- [Deep Research Arena: The First Exam of LLMs' Research Abilities via Seminar-Grounded Tasks](https://doi.org/10.1609/aaai.v40i39.40620)（AAAI-26）—— KAE 引用覆盖维度
- [DeepResearch Bench II: Diagnosing Deep Research Agents via Rubrics from Expert Report](https://doi.org/10.48550/arXiv.2601.08536)（arXiv）—— rubric 原子写法、自评闸门、工具层泄漏屏蔽
- [WebWeaver: Structuring Web-Scale Evidence with Dynamic Outlines for Open-Ended Deep Research](https://doi.org/10.48550/arXiv.2509.13312)（arXiv）—— 证据记忆库形态、分节写作与 citation 锚定
- [Deep Researcher Reflect Evo: Sequential Plan Refinement for Deep Research Agents](https://doi.org/10.48550/arXiv.2601.20843)（arXiv）—— 中央研究上下文与层间 plan 反思
- [DeepPlanner: Scaling Planning Capability for Deep Research Agents via Advantage Shaping](https://doi.org/10.18653/v1/2026.findings-acl.370)（ACL 2026 Findings）—— "显式 plan + 允许修订"的规划哲学
- [FS-Researcher: Test-Time Scaling for Long-Horizon Research Tasks with File-System-Based Agents](https://doi.org/10.18653/v1/2026.acl-long.288)（ACL 2026）—— 逐节写作的消融口径与节级引用闸门

详见 [NOTICE](NOTICE)。
