# gptr-java · 构建与运行指南

> 面向**克隆后想跑起来**的读者。本文不含任何本机特定路径、代理或私有工具。
> 内部开发环境说明另见开发用的 `BUILD.md`（不随发布树发布）。

## 0. 前置要求

| 工具 | 版本 | 验证 |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker | Docker Desktop + Compose | `docker --version && docker compose version` |

## 1. 最快路径：先不配任何 API key 跑通全链路

worker 默认是 **mock LLM**（`application.yml` 的 `gptr.clients.llm-provider: mock`），
所以**没有 LLM key 也能把整条流水线跑通**——用来确认环境、看八节点图、读观测台都够用。
（mock 产出的报告内容无意义，只用于验证链路。）

```bash
# ① 起基础设施：Postgres:5432 + MinIO:9000/9001 + 爬虫:8000
docker compose up -d --build
docker compose ps                      # 等 postgres 变 healthy

# ② 起两个服务（两个终端；都在仓库根目录执行）
mvn -pl gptr-worker spring-boot:run    # 消费任务（默认 mock LLM）
mvn -pl gptr-api spring-boot:run       # 观测台：http://localhost:8080/dashboard/

# ③ 提交一个任务
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"query":"你的研究问题","config":"{\"mode\":\"deep_research\",\"breadth\":2,\"depth\":2}"}'
```

八节点图跑通的判据是 `/events` 里 `type=ACTIVITY` 的 `label` 序列（每个节点成对出现）：

```
research_plan → generate_queries → search → scrape → curate_sources
  → extract_learnings → plan_reflect → follow_up_queries
  → (followUpDriven 时回到 search 再走一轮) → 逐节写作
```

> ⚠️ **检索器必须显式指定**：任务 `config` 里若不写 `retriever`，会回落到默认的
> `duckduckgo`。该检索器对"代理出口 IP + 自动化"会弹验证码而返回空，表现为
> `search: all N sub-queries failed`。**建议显式写 `"retriever":"bocha"`**（见 §2）。

## 2. 换成真实 LLM（三种都行，任选其一）

LLM 走 **OpenAI 兼容**协议，`base-url` / `model` / `api-key` **都可以在观测台配**
（`http://localhost:8080/dashboard/` → 配置页），也可用启动参数覆盖。**改完需重启 worker。**

| 提供方 | `llm.base-url` | `llm.model` 示例 |
|---|---|---|
| DeepSeek（默认） | `https://api.deepseek.com` | `deepseek-chat` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 通义 Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-plus` |
| **Ollama（本地，零成本）** | `http://localhost:11434/v1` | `qwen2.5:14b`（key 填任意非空值） |

```bash
# 用 DeepSeek 起一个"真实"worker（其余提供方同理，换 base-url/model 即可）
mvn -pl gptr-worker spring-boot:run \
  "-Dspring-boot.run.arguments=--gptr.clients.llm-provider=openai --gptr.clients.search-chain=python"
```

- `--gptr.clients.llm-provider=openai` 是**必须的**：不传就一直是 mock（任务会"成功"，但报告是编的）。
  启动日志里应出现：`LLM provider: openai-compatible (<base-url>, model <model>)`。
- `gptr.clients.search-chain=python` 表示检索走仓库内的爬虫服务（否则用 Java 内置的 DDG 客户端）。
- 检索 key 放同目录 `.env`（模板见 `.env.example`），或同样在观测台配置。

## 3. 编译与单元测试

```bash
mvn -q clean compile
mvn -q test              # 含可读性门禁，见 §6
```

## 4. 集成测试

集成测试在**独立库 `gptr_test`** 上跑（`-Pintegration` 经 surefire 注入 `spring.datasource.url`），
**不会碰开发库 `gptr`**：

```bash
docker compose up -d
# 只需一次：建测试库（表由 Flyway 自动迁移）
docker exec gptr-postgres psql -U gptr -d postgres -c 'CREATE DATABASE gptr_test OWNER gptr;'
mvn test -Pintegration
```

> ⚠️ 各集成测试在 `@BeforeEach` 里执行 `TRUNCATE TABLE tasks CASCADE` / `TRUNCATE graph_checkpoints`
> （**全表**）。这正是它们必须连独立库的原因 —— 否则会清空你在开发库里跑过的全部任务记录，
> 以及 checkpoint 里的证据库（`/tasks/{id}/evidence` 依赖它）。

覆盖：队列生命周期 / 租约回收 / 取消 / 失败路径、检索降级链、LLM 429→QUOTA、预算超限、
webhook 签名投递与重试、MinIO 报告存储、WS 事件推送、深研图端到端（14 个测试类 / 22 个用例）。

## 5. 改了代码后如何让服务生效（**常见坑**）

**一句话**：改了哪个模块，就先 `install` 哪个模块；`spring-boot:run` 不会替你重建依赖。

| 你改了 | 该怎么做 |
|---|---|
| 只改 `gptr-worker` / `gptr-api` | 直接 `spring-boot:run`（它会编译本模块）|
| 改了 `gptr-engine` / `gptr-integration` / `gptr-common` | **必须先 install** |

```bash
# ① 先把依赖模块装进本地仓库（-am = 连带其依赖一起构建）
mvn -B -pl gptr-engine -am install -DskipTests
# ② 再启动 worker（此时才取到刚装上的新 jar）
mvn -pl gptr-worker spring-boot:run "-Dspring-boot.run.arguments=--gptr.clients.llm-provider=openai --gptr.clients.search-chain=python"
```

两个原因：**①** `mvn -pl gptr-worker spring-boot:run` 的依赖来自**本地仓库的 jar**，
而不是 `target/classes` —— 改了引擎后重启也没用；
**②** 不要写成 `-pl gptr-worker -am spring-boot:run`，`-am` 会把父聚合项目选进来，
而父 pom 没有 main class ⇒ 报 `Unable to find a suitable main class`。

命令必须在**仓库根目录**执行，否则报 `Could not find the selected project in the reactor`。

### 5.1 两层检索命名（别混）

| 层 | 键 | 取值 | 含义 |
|---|---|---|---|
| Java 侧 | `gptr.clients.search-chain`（启动参数）| `duckduckgo` / `python` | 用哪个 `SearchClient` 实现 |
| crawler 侧 | 任务 config 的 `retriever` | `bocha` / `duckduckgo` / … | 爬虫内部用哪个检索器 |

两者**名字不通用**：把 `bocha` 填进 `search-chain` 会直接启动失败
（`unknown real search source 'bocha' (supported: duckduckgo, python)`）。

### 5.2 监控任务

```bash
curl http://localhost:8080/api/v1/tasks/<id>            # status / cost / errorCode
curl http://localhost:8080/api/v1/tasks/<id>/events     # 阶段与节点活动（ACTIVITY.label = 节点名）
curl http://localhost:8080/api/v1/tasks/<id>/evidence   # 证据库（每条 note 的 insight/quote/sourceUrl）
curl http://localhost:8080/api/v1/tasks/<id>/report     # 报告正文（Markdown）
```

worker 日志里的链路漏斗：

```
[chain] 来源：检索 N（镜像去重后 N）→ 取名 N → 抓成 N(有效 N) | 事实：note N（来源 N，其中未读 N）→ 引用 N 源/N 次
```

## 6. 可读性判据门禁

`mvn test` 会**自动**执行门禁（`ReadabilityGateTest` 调 `scripts/check-readability.ps1`）：
**硬判据违反即 BUILD FAILURE**。单独运行：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-readability.ps1   # Windows
pwsh       -NoProfile -File scripts/check-readability.ps1                            # PowerShell 7
```

12 项判据 = **8 项硬判据**（单行长度 / 块 lambda / 嵌套 lambda / 内联全限定名 / catch 丢异常 /
嵌套三元 / 未使用 import / 嵌套层数）+ **4 项棘轮**（方法行数 / 参数个数 / record 分量 /
仅凭注释的 catch）。棘轮是"只许下调"的存量基线，超标打 `[WARN]` 且**不中断构建**。

⚠️ 无 PowerShell 的环境会被 `assumeTrue` **跳过而非失败** —— 那等于该环境没有门禁。

## 7. 常见问题

- **Maven 依赖下载慢**：用本机/组织的镜像仓库（`settings.xml`），与仓库本身无关。
- **集成测试报 Mockito attach 错误**：`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
  已强制 subclass maker，**不要删除**。
- **Postgres 未启动**：集成测试连不上 `localhost:5432`，先 `docker compose up -d`。
- **后台服务残留**：终止 `spring-boot:run` 时确认 java 子进程一并退出（否则会以真实配置
  在后台抢任务，干扰集成测试）。
- **端口探测不可信**：某些环境下 `Get-NetTCPConnection` 会报告"端口空闲"而服务其实在跑。
  **用 HTTP 请求判断**，不要只看监听表。
- **`DEEPSEEK_API_KEY` 拿不到**：Shell 会话可能没继承用户级环境变量，启动前显式传入：
  ```powershell
  $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')
  ```
- **PowerShell 传 `-D` 参数报 "Unknown lifecycle phase"**：点号会被拆开，**必须加引号**：
  `mvn "-Dtest=XxxTest" "-Dsurefire.useFile=false"`。
- **`config` 静默丢失 → 任务以 flat 模式跑**：PowerShell 里布尔值是 `$true`/`$false`，
  写成 `true` 会让整个 hashtable 字面量构造失败、`config` 实际传成 `null`，而 API 不报错，
  结果任务用默认配置跑完。**用 `ConvertTo-Json` 构造后先打印确认字段完整再提交。**
- **Windows 只有 Windows PowerShell 5.1**：仓库脚本已兼容 5.1 与 7，并在脚本内显式设
  `[Console]::OutputEncoding` 为 UTF-8，否则 5.1 按 GBK 输出会让调用方读到乱码。
- **抓取返回空 `contents`**：爬虫容器出不去网。给 `.env` 配 `HTTP_PROXY`/`HTTPS_PROXY`
  （注意本地代理常只监听 `127.0.0.1`，容器在自己的网络命名空间里够不着宿主机），
  或让容器直连可达的站点。检索走博查为国内直连，不受影响。

## 8. 仓库约定：blame 忽略纯注释提交

仓库根有 `.git-blame-ignore-revs`，列出**只改注释 / 格式**的提交——它们会遮蔽真实作者，
排查业务问题时做 blame 会被误导到"改注释的人"而不是"写这段逻辑的人"。

启用一次即可（GitHub 的 blame 视图会自动读取该文件，无需配置）：

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

**收录新提交前必须复核**（文件头部给了命令）：该提交的"非注释改动行数"必须为 **0**；
含任何行为改动的提交**不得收录** —— 否则会把真实修改藏起来，比不收录更糟。
