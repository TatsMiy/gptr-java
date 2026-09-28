gptr-pycrawler
================

无状态爬虫服务（FastAPI）：复用 gpt-researcher 的检索器与抓取器能力，
供 gptr-java worker 以 HTTP 方式调用（/search /scrape /health）。

来源与许可
----------
本项目自开源项目 [gpt-researcher](https://github.com/assafelovic/gpt-researcher)
（Apache License 2.0）提取精简：
- `pycrawler/retrievers/`：21 个检索器（裁剪 mcp），引擎模块与源码保持上游一致
- `pycrawler/scraper/`：9 类抓取后端（裁剪为惰性注册，未改动各后端实现；
  其中 `trafilatura` 是本仓新增的主内容抽取后端，非上游）
- `pycrawler/utils/`：workers / rate_limiter / url_security（SSRF 校验）
- `retriever_factory.py`：自 `gpt_researcher/actions/retriever.py` 提取的工厂

上游版本基线：GitHub 主分支（2026-xx 下载，zip 无 commit 号）。
Apache-2.0 全文见 LICENSE；各文件保留上游版权头。

与上游的差异（提取裁剪）
-------------------------
1. 无 gpt-researcher 研究编排依赖（agent/skills/actions/llm_provider/…）；
2. 检索器/抓取后端一律**惰性导入**（retriever_factory / scraper.SCRAPER_BACKENDS）——
   每个引擎/后端的重型可选依赖（tavily/exa/selenium/firecrawl/langchain…）
   只在被选中时才 import，未装则报 ImportError 而非整包导入即崩；
3. 裁剪 mcp 检索器（依赖 llm_provider 与 researcher 注入，非无状态服务所需）；
4. `/search` 响应透传 `content`（raw_content 截断 8000 字符，全文型检索器可用）；
5. `/scrape` 默认后端为 **`trafilatura`**（常量 `api.DEFAULT_SCRAPER_BACKEND`，可改）；
6. **新增 `trafilatura` 抓取后端**（本仓自有，不在上游）：复用默认后端的抓取与结构特征，
   只把"取正文"一步换为 trafilatura 的主内容抽取，用于**去掉推荐位/分类列表这类站点框架**
   （实测跨页重复框架占比：整页文本中位 5% / p90 54% → 抽取后中位 0% / p90 6%）。
   因其为默认后端，依赖在 `requirements.txt` 中是**正式项**（会带入 courlan/htmldate/justext 等约 10 个包）；
   抽不出主内容的页返回空正文，由 `/scrape` 记 `empty_content` 交代，**不**回退整页。
7. **新增 `pycrawler/stats.py` 与 `GET /stats`**（本仓自有，不在上游）：进程内计数，零新依赖。
   按失败原因 / 页型 / 状态码类 / 域名 / 耗时桶 / 截断累加，并记录检索器结果类别与每查询条数。
   几点口径：汇总一律由明细求和，不另设计数器；标签只用封闭词表或封顶集合（域名 Top-10 + 归并桶，
   调用方传的字符串永不成为标签）；计数失败只记 warn，不影响抓取；重启即清零，`uptime_s`
   就是这些数字覆盖的时间窗。

同步策略
--------
上游更新时按需手动同步对应文件（注意保留惰性化改动：
`retrievers/__init__.py`、`retriever_factory.py`、`scraper/__init__.py`、
`scraper/scraper.py` 的 SCRAPER_BACKENDS/_load_backend）。

API
---
- GET  /health   报告**本机可查**的依赖状态：通路是否配置 / 通路端口是否收连接 /
                 上次成功抓到页距今多久。判定（`ok`/`degraded`）与其依据同在 `checks` 里；
                 **不发起外部网络请求**，`degraded` 也返回 200（服务还在答，只是依赖不通）
- GET  /stats    进程内计数快照（重启即清零；单 uvicorn worker，见 `Dockerfile` 的 CMD）
- POST /search   body: {"query","max_results"=5,"retriever"="duckduckgo"}
- POST /scrape   body: {"urls":[...]}

本地运行：`uvicorn pycrawler.api:app --host 0.0.0.0 --port 8000`
