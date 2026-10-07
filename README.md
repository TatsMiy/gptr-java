# gptr-java

**中文** | [English](README.en.md)

提交一个问题，它分多轮检索、先出大纲再逐节写作，最后给出一份**每句话都能点回逐字原文**的报告。
自己部署的单机服务：Java 21 + Spring Boot 3.5，Postgres 做任务底座，配一个网页观测台可以实时看进展。

> **一次任务长什么样**：输入一个问题，得到一份分节报告 —— 每节末尾是 `[n]` 引用，文末是程序生成的
> 来源表，每条引用都能点回抓到的原文；观测台里能实时看到每一层查询与每条证据的抓取/核验状态。

**与 [gpt-researcher](https://github.com/assafelovic/gpt-researcher) 的关系**：研究主链路是 Java
**重写**（不是包装），`crawler/` 子目录由该项目**提取**复用（Apache-2.0，自带 `LICENSE`/`NOTICE`）。
逐项差异见《[与 gpt-researcher 的关系与差异](#与-gpt-researcher-的关系与差异)》，文献借鉴与有意偏离见
《[来源、借鉴与偏离](#来源借鉴与偏离)》。

**技术栈**：Java 21 · Spring Boot 3.5 · LangGraph4j · Postgres · Flyway · MinIO · Resilience4j ·
vanilla JS 观测台

## 特性

**研究能力**

- **多轮递归，而不是一问一答**：先澄清问题，再逐层检索；每层结束把"已覆盖什么、还缺什么"写进研究计划，
  下一层的查询由这份计划生成。
- **先大纲、再逐节写作**：每节单独查证据、单独写，并把已写好的前文注入本节，减少重复。
- **可审计的证据库**：每条结论都带逐字原文与来源 URL，以 JSON 入库，事后可以逐条复核。

**可信度**

- **只允许引用真实来源**：模型可用的 URL 限定在"已检索且已提炼"的白名单内，越界引用会被核验拦下。
  （在一轮同题对照里实测：引用一致性 1.00、矛盾陈述 0；样本与边界见下文《同题对照》）
- **参考文献由程序生成**：正文引用编号为 `[n]`，文末来源表由代码生成，不靠模型自行排列。
- **判官先自检再打分**：评测器先注入已知答案句测判官，判官可信之后才采信它的评分。
- **评测器**：每轮输出双口径准确率、幻觉率（保守下限 / 含不确定上限）、引用一致性与覆盖分，
  支持 A/B 双跑盲判。自身单测：`mvn -pl gptr-benchmark test`。

**工程**

- **任务底座**：Postgres 队列（原子出队）+ 租约续租守护 + 步骤 / 时长 / 成本三重预算 + 优雅重试。
- **单页观测台**（`/dashboard/`）：任务时间线、成本统计、报告阅读（渲染 / 原文两视图）、证据库浏览、
  配置面板、Fork 为蓝图；另有抓取体检（检索 → 取名 → 抓成 → 有效的漏斗、静默丢失、引用未读源比例）。
  前端无框架、无构建链（渲染用仓内 vendored 的 `marked` + `DOMPurify`）。
- **测试与门禁**：engine 248 单测 · crawler 98 单测（`python -m unittest discover -s tests`）·
  benchmark 45 单测 · 集成 14 类 / 22 用例；可读性门禁随 `mvn test` 自动执行。

## 快速开始

前置：JDK 21、Maven 3.9+、Docker（含 Compose）。命令默认在仓库根目录执行。

```bash
# 1) 多模块项目：先把各模块装进本地仓库（只需一次；改了 engine/integration/common 之后要重跑）
mvn -q clean install -DskipTests

# 2) 基础设施：Postgres + MinIO + 爬虫服务（searxng 是可选检索源，见下）
docker compose up -d --build postgres minio crawler
docker compose ps                     # 等 postgres 变成 healthy

# 3) 起服务（两个终端；默认是 mock 模型，不需要任何 key）
mvn -pl gptr-worker spring-boot:run   # 终端 1：worker
mvn -pl gptr-api    spring-boot:run   # 终端 2：API + 观测台 → http://localhost:8080/dashboard/

# 4) 提交任务（也可以直接在观测台上提交；<id> 取返回体里的 id 字段）
curl -X POST http://localhost:8080/api/v1/tasks -H "Content-Type: application/json" \
  -d '{"query":"你的研究问题","config":"{\"mode\":\"deep_research\",\"breadth\":3,\"depth\":2}"}'

# 5) 看结果
curl http://localhost:8080/api/v1/tasks/<id>          # status / cost / errorCode
curl http://localhost:8080/api/v1/tasks/<id>/events   # 节点活动（ACTIVITY.label = 节点名）
curl http://localhost:8080/api/v1/tasks/<id>/report   # 报告正文（Markdown）
```

**两点说明**（不看容易卡）：

- **默认模型是 mock**，报告是编的，只用来验证链路通不通。换真实模型：给 worker 加
  `--gptr.clients.llm-provider=openai` 并配一个 OpenAI 兼容端点的 key（DeepSeek / OpenAI / 通义 / 智谱 /
  本地 Ollama 都行）。
- **检索源必须有一个**。任务里不写 `retriever` 就会走默认降级链（仓库内 crawler → Java 原生 DuckDuckGo），
  而 DuckDuckGo 在多数网络下返回空、表现为"所有子查询失败"。零成本的做法是自己起 SearXNG：
  `cp searxng/settings.yml.example searxng/settings.yml` → `docker compose up -d searxng`，任务里写
  `"retriever":"searx"`；有博查 key 则写 `"retriever":"bocha"`（key 放 `.env`，模板见 `.env.example`）。
  （不带服务名的 `up` 会把 searxng 一起拉起，而它需要先准备那份配置，所以上面显式列了三个基础服务。）

MinIO 控制台：http://localhost:9001（`gptr` / `gptr12345`，仅本地开发默认值）。构建与排错见
[BUILD.md](BUILD.md)。

## 来源、借鉴与偏离

本项目研读并逐条判决了 **7 篇** 2025–2026 的 deep research 论文，下表是**实际落点** —— 每条标注为
"已实施 / 参考 / 有意偏离 / 未采纳"（论文全称与链接见文末《许可与致谢》）：

| 机制 | 论文 | 落点 |
|---|---|---|
| 三态判定协议（Supported / Contradictory / Inconclusive）+ 反证判据 | [DeepFact](https://doi.org/10.18653/v1/2026.acl-long.1586) | ✅ 已实施：每句 evidence 输出三态判定，并配 verdict 白名单 |
| micro-gold 判官自检（注入已知答案句先测判官，再信它的分）| DeepFact | ✅ 已实施：30 条机器锚定探针（1:4 对抗注入）|
| KAE-lite 引用覆盖（KSR / KCR / KOR + 第四态 `abstain`）| [DeepResearch Arena](https://doi.org/10.1609/aaai.v40i39.40620) | ✅ 已实施：从"引用"出发查报告是否把关键事实写全 |
| 题集"禁止来源"字段 + 泄漏率上报（工具层屏蔽 URL）| [DeepResearch Bench II](https://doi.org/10.48550/arXiv.2601.08536) | ✅ 已实施：`blockedUrls` + 检索结果层过滤（比论文"建议但未落地"更彻底）|
| rubric 原子化 / 内容承载写法 + 自评闸门 | DeepResearch Bench II | 参考（用于题库入库流程）|
| 证据条目结构（`insight` 与 `quote` 分离）| [WebWeaver](https://doi.org/10.48550/arXiv.2509.13312) | ✅ 已实施，并补 canonical 规范化与授权集校验 |
| 逐节写作 + 节级引用闸门 | WebWeaver + [FS-Researcher](https://doi.org/10.18653/v1/2026.acl-long.288) | ✅ 已实施。⚠️ 两篇结论**相反**：WebWeaver 警告逐节写作会让内容与风格不连贯、主张节间连续叙事；FS-Researcher 的消融则认为逐节更优。本项目早期只采纳"上下文隔离"，后来补上"已写节注入"找回全局连贯（重复度下降，双跑一致）|
| 层间 plan 反思 / 中央研究状态 | [Reflect-Evolve](https://doi.org/10.48550/arXiv.2601.20843) | ✅ 已实施：层末写"已覆盖 vs 缺口"的节点，下一轮查询生成读它 |
| 显式 plan + 允许修订 | [DeepPlanner](https://doi.org/10.18653/v1/2026.findings-acl.370) | 哲学采纳（研究计划作为受管理状态；其 RL 训练管线不做）|
| 蒸馏 / 脱水选句 | WebWeaver | ⚠️ **有意偏离**：论文用 LLM 生成 query-relevant summary（有损、无保真审计）；本项目改为「只删不改 + 程序化保真前检」—— `quote` 的价值恰恰是"逐字" |
| "写完即剪"（write-then-prune）| WebWeaver | ❌ **未采纳**：本项目不累积证据（每节按需取用），无物可剪 |
| 来源质量闸（curate）| [gpt-researcher](https://github.com/assafelovic/gpt-researcher) `SourceCurator` | ⚠️ 移植时自加了 prompt 输入预算（原版没有）。实测候选一多会把已抓取的正文块整批丢掉（可用正文块 62 → 34）⇒ 已翻回默认关 |

## 与 gpt-researcher 的关系与差异

`crawler/` 是**提取**（非重写），研究主链路是**重写**。以下是可验证的结构性差异，不是形容词：

| 维度 | gpt-researcher | 本项目 |
|---|---|---|
| 运行时 | Python + asyncio，单进程 | Java 21 + Spring Boot；api / worker / crawler 三进程 |
| 任务底座 | 进程内状态，无队列 | Postgres 队列 + 租约 + 预算 + 重试 + `ownerToken` fence |
| 证据层 | 上下文为拼接字符串 | 结构化证据库（`insight` / `quote` / `sourceUrl` 三分离，JSON 入库）|
| 引用 | prompt 内软约束 | 授权来源集（只允许引用已检索且已提炼的 URL）+ 机械 References + canonical 规范化 |
| 质量评测 | `evals/`（模型质量） | 可审计评测器：判官自检 + 双口径 + 三态幻觉反证 + A/B 双跑盲判 |
| 来源质量闸 | `SourceCurator`，默认关 | 同一机制，曾翻为默认开；后因实测会丢素材而翻回默认关（见上表）|

**同题对照**（两个实现各跑一次，六维盲判 + 双跑换序，只认一致结果）：

| 轮次 | 题数 | `overall` | 说明 |
|---|---|---|---|
| 2026-09-13 | 4 | **2 : 2** | 本项目在 depth / citation / honesty 三维 2:1 领先 |
| 2026-09-20 | 2 | **本项目 2 : 0** | 其中一道"观点类"题从原版的 5 维领先被翻盘 |
| 2026-09-23 | 4 | 3 题判本项目优、1 题两轮相反（作废） | 8 次判分汇总：depth 6:2、honesty 7:1 归本项目；structure 1:7、语言 1:7 归原版；citation 3:4 **不相上下** |

合起来看，两边是**互补**而不是一边倒：本项目强在**分析深度与诚实**（标注证据状态、把推断与事实分开），
原版强在**结构贴合题意与文字呈现**。最后一轮另有**人工核对**：判官三条论断成立，一条"只靠单篇博客"
说过头了（那篇约占一半引用，但另有 22 条 arXiv + 8 条 Springer 一手来源；原版那份反而没有一手学术源）。

⚠️ **边界**：样本仅 2–4 题、每题每轮只有 1 个判官；原版有两轮用的是旧报告（未按对齐条件重跑）；
判官与被测模型同源。上表数字与前面提到的"引用一致性 1.00 / 矛盾陈述 0"都只作方向性参考，
**不构成全面优劣结论**。

## 检索与抓取（可插拔）

- 检索降级链 `gptr.clients.search-chain`（逗号分隔，顺序尝试），**默认 `python,duckduckgo`**：
  - `python` — 仓库内 `crawler/` 服务（20 个搜索引擎适配器，推荐完整档）
  - `duckduckgo` — 零 key 直连（Java 原生）
  - `name:ok|transient|quota|permanent` — mock 源（测试 / 演示）
- 任务级检索器由任务 config 的 `retriever` 指定（如 `searx` / `bocha`）；**建议显式指定** ——
  默认那只在部分网络环境下不可用，不指定会表现为"所有子查询失败"。
- 密钥类配置（各检索器 API key、模型、检索链）可在观测台「⚙ 配置」面板持久化
  （保存后重启 worker 生效，无热加载）。

## 目录结构

```
gptr-java/
├── docker-compose.yml        # Postgres + MinIO + crawler（+ 可选 searxng）
├── searxng/                  # 可选检索源的配置模板（本机配置不入库）
├── crawler/                  # 无状态 Python 爬虫服务（自 gpt-researcher 提取，Apache-2.0）
├── gptr-common/              # 领域模型 / 状态机 / JPA / Flyway / 任务服务 / 事件日志
├── gptr-integration/         # 外部客户端接口 / 弹性层 / 报告存储 / UrlSecurity
├── gptr-engine/              # 研究引擎：深研图 / 逐节写作 / 证据库 / 引用核验
├── gptr-api/                 # REST + WebSocket + 观测台单页（/dashboard/）
├── gptr-worker/              # 队列消费者（出队 / 租约 / 预算 / 存储）
├── gptr-benchmark/           # 评测器（准确率 / 幻觉率 / 引用 / 覆盖 + A/B）
└── BUILD.md / README.md      # 构建测试与使用指南
```

## 许可与致谢

Apache-2.0，见 [LICENSE](LICENSE)。归属与致谢：

**项目**

- **[gpt-researcher](https://github.com/assafelovic/gpt-researcher)**（Apache-2.0，
  Copyright (c) Assaf Elovic and contributors）—— 架构与 prompt 资产派生自该项目；
  `crawler/` 为其提取精简版，携带其自有 `LICENSE` / `NOTICE`

**论文**（逐篇的借鉴点与偏离见《[来源、借鉴与偏离](#来源借鉴与偏离)》）

- [DeepFact: Co-Evolving Benchmarks and Agents for Deep Research Factuality](https://doi.org/10.18653/v1/2026.acl-long.1586)（ACL 2026）—— 三态判定协议与 micro-gold 判官自检
- [Deep Research Arena: The First Exam of LLMs' Research Abilities via Seminar-Grounded Tasks](https://doi.org/10.1609/aaai.v40i39.40620)（AAAI-26）—— KAE 引用覆盖维度
- [DeepResearch Bench II: Diagnosing Deep Research Agents via Rubrics from Expert Report](https://doi.org/10.48550/arXiv.2601.08536)（arXiv）—— rubric 原子写法、自评闸门、工具层泄漏屏蔽
- [WebWeaver: Structuring Web-Scale Evidence with Dynamic Outlines for Open-Ended Deep Research](https://doi.org/10.48550/arXiv.2509.13312)（arXiv）—— 证据记忆库形态、分节写作与 citation 锚定
- [Deep Researcher Reflect Evo: Sequential Plan Refinement for Deep Research Agents](https://doi.org/10.48550/arXiv.2601.20843)（arXiv）—— 中央研究上下文与层间 plan 反思
- [DeepPlanner: Scaling Planning Capability for Deep Research Agents via Advantage Shaping](https://doi.org/10.18653/v1/2026.findings-acl.370)（ACL 2026 Findings）—— "显式 plan + 允许修订"的规划哲学
- [FS-Researcher: Test-Time Scaling for Long-Horizon Research Tasks with File-System-Based Agents](https://doi.org/10.18653/v1/2026.acl-long.288)（ACL 2026）—— 逐节写作的消融口径与节级引用闸门

详见 [NOTICE](NOTICE)。
