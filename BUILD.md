# gptr-java · 构建与运行

面向克隆后想尽快跑起来的人。命令默认在仓库根目录执行。

| 工具 | 版本 | 验证 |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker | Docker Desktop + Compose | `docker --version && docker compose version` |

## 1. 五分钟跑通（不需要 LLM key）

worker 默认是 mock LLM，整条流水线不配任何模型 key 也能跑通，用来确认环境、看八节点图、读观测台都够用。
（mock 产出的报告内容无意义，只用于验证链路。）

```bash
# ① 多模块项目：先把各模块装进本地仓库（只需一次；改了 engine/integration/common 之后要重跑）
mvn -q clean install -DskipTests

# ② 基础设施：Postgres:5432 + MinIO:9000/9001 + 爬虫:8000
docker compose up -d --build postgres minio crawler
docker compose ps                      # 等 postgres 变 healthy

# ③ 起两个服务（两个终端）
mvn -pl gptr-worker spring-boot:run    # 终端 1：消费任务（默认 mock LLM）
mvn -pl gptr-api spring-boot:run       # 终端 2：观测台 http://localhost:8080/dashboard/

# ④ 提交一个任务（也可以直接在观测台上提交；<id> 取返回体里的 id 字段）
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"query":"你的研究问题","config":"{\"mode\":\"deep_research\",\"breadth\":2,\"depth\":2}"}'

# ⑤ 看结果
curl http://localhost:8080/api/v1/tasks/<id>            # status / cost / errorCode
curl http://localhost:8080/api/v1/tasks/<id>/events     # 阶段与节点活动（ACTIVITY.label = 节点名）
curl http://localhost:8080/api/v1/tasks/<id>/evidence   # 证据库（每条 note 的 insight/quote/sourceUrl）
curl http://localhost:8080/api/v1/tasks/<id>/report     # 报告正文（Markdown）
```

跑通的判据是 `/events` 里 `type=ACTIVITY` 的 `label` 序列（每个节点成对出现）：

```
research_plan → generate_queries → search → scrape → curate_sources
  → extract_learnings → plan_reflect → follow_up_queries
  → (followUpDriven 时回到 search 再走一轮) → 逐节写作
```

**⚠️ 检索源必须有一个。** 任务 `config` 里不写 `retriever` 就会走默认降级链（先调仓库内 crawler，再回落
Java 原生 DuckDuckGo），而 DuckDuckGo 在多数网络下会返回空，表现为 `search: all N sub-queries failed`。
两条出路：

- 零成本：自己起一个 SearXNG（见《自建 SearXNG》），任务里写 `"retriever":"searx"`；
- 有博查 key：`.env` 里填 `BOCHA_API_KEY`（模板见 `.env.example`），任务里写 `"retriever":"bocha"`。

> 不带服务名的 `docker compose up -d --build` 会把 searxng 一起拉起来，而它需要先准备
> `searxng/settings.yml`（该文件不入库，只有模板）—— 所以上面 ② 显式列出了三个基础服务。
> 若已经误建过同名目录（Docker 对缺失的挂载源会建目录），先删掉它再复制模板。

可选：`cp .env.example .env` 后按需填写；MinIO 控制台在 http://localhost:9001（`gptr` / `gptr12345`，
仅本地开发默认值）。

## 2. 换成真实 LLM（任选其一）

LLM 走 OpenAI 兼容协议，`base-url` / `model` / `api-key` 都能在观测台配置页改，也可用启动参数覆盖。
**改完需重启 worker。**

| 提供方 | `llm.base-url` | `llm.model` 示例 |
|---|---|---|
| DeepSeek（默认） | `https://api.deepseek.com` | `deepseek-chat` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 通义 Qwen | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-plus` |
| Ollama（本地，零成本） | `http://localhost:11434/v1` | `qwen2.5:14b`（key 填任意非空值） |

```bash
mvn -pl gptr-worker spring-boot:run \
  "-Dspring-boot.run.arguments=--gptr.clients.llm-provider=openai --gptr.clients.search-chain=python"
```

- `--gptr.clients.llm-provider=openai` 是必须的：不传就一直是 mock（任务会"成功"，但报告是编的）。
  启动日志里应出现 `LLM provider: openai-compatible (<base-url>, model <model>)`。
- `gptr.clients.search-chain` 是检索实现的降级链，默认 `python,duckduckgo`（先调仓库内爬虫服务，
  失败回落 Java 原生 DDG）；传 `python` 即只用爬虫服务。
- 检索 key 放同目录 `.env`（模板 `.env.example`），或同样在观测台配置。

## 3. 编译与测试

```bash
mvn -q clean compile
mvn -q test                       # 含可读性门禁，见《给改这个仓库的人》
```

集成测试在独立库 `gptr_test` 上跑（`-Pintegration` 注入测试库地址），不会碰开发库 `gptr`：

```bash
docker compose up -d postgres     # 复用第 1 节起的 Postgres
# 只需一次：建测试库（表由 Flyway 自动迁移）
docker exec gptr-postgres psql -U gptr -d postgres -c 'CREATE DATABASE gptr_test OWNER gptr;'
mvn test -Pintegration
```

各集成测试在 `@BeforeEach` 里执行 `TRUNCATE TABLE tasks CASCADE` / `TRUNCATE graph_checkpoints`（**全表**）
—— 这正是它们必须连独立库的原因，否则会清空开发库里跑过的任务记录与 checkpoint 里的证据库。
覆盖：队列生命周期 / 租约回收 / 取消 / 失败路径、检索降级链、LLM 429→QUOTA、预算超限、webhook 签名
投递与重试、MinIO 报告存储、WS 事件推送、深研图端到端（14 个测试类 / 22 个用例）。

## 4. 改了代码后如何让服务生效

改了哪个模块，就先 install 哪个模块；`spring-boot:run` 不会替你重建依赖。

| 你改了 | 该怎么做 |
|---|---|
| 只改 `gptr-worker` / `gptr-api` | 直接 `spring-boot:run`（它会编译本模块）|
| 改了 `gptr-engine` / `gptr-integration` / `gptr-common` | **必须先 install** |

```bash
mvn -B -pl gptr-engine -am install -DskipTests     # ① 装进本地仓库（-am 连带其依赖）
mvn -pl gptr-worker spring-boot:run                # ② 再启动，此时才取到新 jar
```

原因：`mvn -pl gptr-worker spring-boot:run` 的依赖来自**本地仓库的 jar**，不是 `target/classes`，
所以改了引擎后重启也没用。另外别写成 `-pl gptr-worker -am spring-boot:run`：`-am` 会把父聚合项目
选进来，而父 pom 没有 main class，会报 `Unable to find a suitable main class`。

## 5. 两层检索命名（别混）

| 层 | 键 | 取值 | 含义 |
|---|---|---|---|
| Java 侧 | `gptr.clients.search-chain`（启动参数）| `duckduckgo` / `python` / `python,duckduckgo`（默认，降级链）| 用哪个 `SearchClient` 实现 |
| crawler 侧 | 任务 config 的 `retriever` | `searx` / `bocha` / `duckduckgo` / … | 爬虫内部用哪个检索器 |

两者名字不通用：把 `bocha` 填进 `search-chain` 会直接启动失败
（`unknown real search source 'bocha' (supported: duckduckgo, python)`）。

## 6. 自建 SearXNG（可选，零 key 的聚合检索源）

```bash
# ① 准备实例配置（settings.yml 不入库，只提供模板）
cp searxng/settings.yml.example searxng/settings.yml
# ② 起实例（只绑回环；浏览器可直接用它的界面 http://127.0.0.1:8888/ 试搜）
docker compose up -d searxng
# ③ 起 crawler（它带 SEARX_URL，同网络用服务名互访）
docker compose up -d crawler
```

三个环境变量写在 `.env`（模板见 `.env.example`）：

| 键 | 作用 | 留空时 |
|---|---|---|
| `SEARX_URL` | 实例地址（默认 `http://gptr-searxng:8080`） | 用 compose 里的默认值 |
| `SEARX_ENGINES` | 传给实例的引擎白名单（逗号分隔） | 用实例默认引擎集 |
| `SEARX_LANGUAGE` | 实例的 `language` 参数（如 `zh-CN` / `all`） | 用实例默认 |

用法：任务 config 里写 `"retriever":"searx"`（观测台建任务表单里能选到）。注意别把它填进
`search-chain`（那是另一层命名，见《两层检索命名》）。

两点提醒（细节与理由见 `searxng/settings.yml.example` 内的注释）：

- 建议显式填 `SEARX_ENGINES`：留空时参与聚合的引擎很少；`SEARX_LANGUAGE` 留空反而更宽。
  引擎可用性由出口 IP 决定，换网络要重测。
- `outgoing.proxies` 可以填代理列表，但它不是"高可用轮换"，做轮换要在 SearXNG 之外自己完成。

失败语义：crawler 侧的 `searx` 检索器会读实例返回的 `unresponsive_engines` —— 引擎全被拦时抛异常
（crawler 返 502，Java 侧走重试/熔断/降级链），只有真的没有结果才返回空列表。所以"没搜到"和
"被拦了"是两件不同的事。

## 7. 常见问题

- Maven 依赖下载慢：用本机/组织的镜像仓库（`settings.xml`），与仓库本身无关。
- 集成测试报 Mockito attach 错误：`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
  已强制 subclass maker，不要删。
- 连不上 `localhost:5432`：Postgres 没起，先 `docker compose up -d postgres`。
- 后台服务残留：终止 `spring-boot:run` 时确认 java 子进程一并退出，否则它会以真实配置在后台抢任务。
- 抓取返回空 `contents`：爬虫容器出不去网。给 `.env` 配 `HTTP_PROXY`/`HTTPS_PROXY`（注意本地代理常只
  监听 `127.0.0.1`，容器在自己的网络命名空间里够不着宿主机）。检索走博查是国内直连，不受影响。
- 端口探测不可信：某些环境下 `Get-NetTCPConnection` 会报"端口空闲"而服务其实在跑，用 HTTP 请求判断。
- PowerShell 传 `-D` 参数报 "Unknown lifecycle phase"：点号会被拆开，必须加引号，例如
  `mvn "-Dtest=XxxTest" "-Dsurefire.useFile=false"`。
- **`config` 静默丢失 → 任务以默认配置跑**（PowerShell 专有）：布尔值要写 `$true`/`$false`，
  写成 `true` 会让整个 hashtable 字面量构造失败、`config` 传成 `null`，而 API 不报错。
  用 `ConvertTo-Json` 构造后先打印确认字段完整再提交。
- `DEEPSEEK_API_KEY` 拿不到：Shell 会话可能没继承用户级环境变量，启动前显式传入
  `$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')`。

## 8. 给改这个仓库的人

- `mvn test` 会自动跑可读性门禁（`ReadabilityGateTest`，纯 Java AST 实现，不依赖外部脚本），
  硬判据违反即构建失败。13 项判据 = 8 项硬判据 + 5 项棘轮（棘轮是存量基线，超标只告警）。
  报告落盘 `gptr-engine/target/readability-gate.txt`。
- 仓库根有 `.git-blame-ignore-revs`，列出只改注释/格式的提交。排查业务逻辑时先启用一次：
  `git config blame.ignoreRevsFile .git-blame-ignore-revs`。收录新提交前必须复核它的"非注释改动行数"为 0，
  含行为改动的提交不得收录。
