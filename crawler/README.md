gptr-pycrawler
================

无状态爬虫服务（FastAPI）：复用 gpt-researcher 的检索器与抓取器能力，
供 gptr-java worker 以 HTTP 方式调用（/search /scrape /health）。

来源与许可
----------
本项目自开源项目 [gpt-researcher](https://github.com/assafelovic/gpt-researcher)
（Apache License 2.0）提取精简：
- `pycrawler/retrievers/`：21 个检索器（裁剪 mcp），引擎模块与源码保持上游一致
- `pycrawler/scraper/`：8 类抓取后端（裁剪为惰性注册，未改动各后端实现）
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
5. `/scrape` 钉死 bs 后端（可经代码改 `scraper` 参数放开）。

同步策略
--------
上游更新时按需手动同步对应文件（注意保留惰性化改动：
`retrievers/__init__.py`、`retriever_factory.py`、`scraper/__init__.py`、
`scraper/scraper.py` 的 SCRAPER_BACKENDS/_load_backend）。

API
---
- GET  /health
- POST /search   body: {"query","max_results"=5,"retriever"="duckduckgo"}
- POST /scrape   body: {"urls":[...]}

本地运行：`uvicorn pycrawler.api:app --host 0.0.0.0 --port 8000`
