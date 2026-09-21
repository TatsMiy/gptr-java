"""gptr-pycrawler：无状态爬虫服务（独立精简版，自 gpt-researcher 提取，Apache-2.0）。

端点：
- GET  /health
- POST /search   经 pycrawler.retriever_factory.get_retriever(name) 实例化检索器后调用
- POST /scrape   经 pycrawler.scraper.scraper.Scraper 抓取并清洗

错误分类：429→429、5xx→5xx、4xx→4xx（HTTP 状态透传，Java 弹性层据此分类）。
"""
import logging

from fastapi import FastAPI, HTTPException, Request

from pycrawler.dto import (
    ScrapeRequest,
    ScrapeResponse,
    ScrapedContent,
    SearchRequest,
    SearchResponse,
    SearchResultItem,
)

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("gptr-pycrawler")

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)

app = FastAPI(title="gptr-pycrawler", version="0.1.0")


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/search", response_model=SearchResponse)
async def search(req: SearchRequest, request: Request):
    from pycrawler.retriever_factory import get_retriever

    retriever_cls = get_retriever(req.retriever)
    if retriever_cls is None:
        raise HTTPException(status_code=400, detail=f"unknown retriever: {req.retriever}")

    # 2026-09-19：把请求 header 透传给检索器，使其能取用观测台配置的 key
    # （header 名 <name>_api_key；检索器侧 header 优先 → env 回落）。
    headers = {k.lower(): v for k, v in request.headers.items()}
    try:
        # 2026-09-19：**实例化也纳入 502 语义** —— 缺 key 时 resolve_api_key 抛 ValueError，
        # 此前该调用在 try 之外，会漏成 FastAPI 的 500（破坏 Java 侧的错误分类与降级链）。
        retriever = retriever_cls(req.query, headers=headers)
        if hasattr(retriever, "search_async"):
            raw = await retriever.search_async(req.max_results)
        else:
            raw = retriever.search(req.max_results)
    except HTTPException:
        raise
    except Exception as e:
        logger.warning("search failed (retriever=%s): %s", req.retriever, e)
        raise HTTPException(status_code=502, detail=f"search failed: {e}")

    results = []
    for item in raw or []:
        if isinstance(item, dict):
            raw_content = item.get("raw_content") or item.get("content") or ""
            results.append(SearchResultItem(
                title=item.get("title") or "",
                url=item.get("href") or item.get("url") or "",
                snippet=item.get("body") or item.get("content") or item.get("snippet") or "",
                content=raw_content[:8000] if raw_content else "",  # 全文型结果透传（截断防爆响应）
            ))
    return SearchResponse(results=results)


@app.post("/scrape", response_model=ScrapeResponse)
async def scrape(req: ScrapeRequest):
    from pycrawler.scraper.scraper import Scraper
    from pycrawler.utils.workers import WorkerPool

    if not req.urls:
        return ScrapeResponse(contents=[])

    pool = WorkerPool(max_workers=4)
    scraper = Scraper(urls=req.urls, user_agent=USER_AGENT, scraper="bs", worker_pool=pool)
    try:
        data = await scraper.run()
    except Exception as e:
        logger.warning("scrape failed: %s", e)
        raise HTTPException(status_code=502, detail=f"scrape failed: {e}")

    contents = []
    cap = req.max_chars or 4000
    if cap < 0:
        cap = 4000
    for item in data or []:
        raw = item.get("raw_content") or ""
        if not raw:
            continue
        contents.append(ScrapedContent(
            url=item.get("url") or "",
            title=item.get("title") or "",
            content=raw[:cap],  # 截断防爆响应（sourceDistill 模式由调用方调大）
        ))
    return ScrapeResponse(contents=contents)
