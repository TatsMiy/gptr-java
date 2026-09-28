"""gptr-pycrawler：无状态爬虫服务（独立精简版，自 gpt-researcher 提取，Apache-2.0）。

端点：
- GET  /health
- GET  /stats    进程内计数快照（零新依赖；重启即清零）
- POST /search   经 pycrawler.retriever_factory.get_retriever(name) 实例化检索器后调用
- POST /scrape   经 pycrawler.scraper.scraper.Scraper 抓取并清洗

错误分类：429→429、5xx→5xx、4xx→4xx（HTTP 状态透传，Java 弹性层据此分类）。
"""
import logging
import time

from fastapi import FastAPI, HTTPException, Request

from pycrawler.dto import (
    ScrapeRequest,
    ScrapeResponse,
    ScrapedContent,
    SearchRequest,
    SearchResponse,
    SearchResultItem,
)
from pycrawler.health import health_report
from pycrawler.log_context import (
    LOG_FORMAT,
    REQUEST_ID_HEADER,
    install_request_id_filter,
    set_request_id,
)
from pycrawler.stats import CrawlerStats

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT)
install_request_id_filter()
logger = logging.getLogger("gptr-pycrawler")

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)

# 响应体积护栏：单次调用的正文/全文透传都要截断，不能让响应无上界增长。
DEFAULT_SCRAPE_CHAR_CAP = 4000
SEARCH_CONTENT_CHAR_CAP = 8000

# 默认抓取后端。取"主内容抽取"而非整页文本：实测整页的中位框架占比 5%、
# p90 达 54%（导航/分类列表/推荐位），换用抽取后 p90 降到 6%。
DEFAULT_SCRAPER_BACKEND = "trafilatura"

app = FastAPI(title="gptr-pycrawler", version="0.1.0")

# 进程内计数（单 uvicorn worker ⇒ 进程即全局；重启即清零，见 /stats 的 uptime_s）
STATS = CrawlerStats()


def _count(action, *args):
    """计数失败不得影响抓取：记 warn 后继续，不让 /scrape 变慢或报错。"""
    try:
        action(*args)
    except Exception as e:
        logger.warning("stats update failed (%s): %s", getattr(action, "__name__", action), e)


def _elapsed_ms(started):
    return int((time.monotonic() - started) * 1000)


@app.get("/health")
async def health():
    return health_report(STATS)


@app.get("/stats")
async def stats():
    return STATS.snapshot()


@app.post("/search", response_model=SearchResponse)
async def search(req: SearchRequest, request: Request):
    from pycrawler.retriever_factory import get_retriever

    set_request_id(request.headers.get(REQUEST_ID_HEADER))

    retriever_cls = get_retriever(req.retriever)
    if retriever_cls is None:
        # 未知检索器名照实计数（落在归并标签上，不成为新标签），但它不改变 400 语义
        _count(STATS.record_search, req.retriever, 0, 0, "unknown_retriever")
        raise HTTPException(status_code=400, detail=f"unknown retriever: {req.retriever}")

    # 把请求 header 透传给检索器，使其能取用观测台配置的 key
    # （header 名 <name>_api_key；检索器侧 header 优先 → env 回落）。
    headers = {k.lower(): v for k, v in request.headers.items()}
    started = time.monotonic()
    try:
        # **实例化也纳入 502 语义** —— 缺 key 时 resolve_api_key 抛 ValueError；
        # 若该调用在 try 之外，会漏成 FastAPI 的 500，破坏 Java 侧的错误分类与降级链。
        retriever = retriever_cls(req.query, headers=headers)
        if hasattr(retriever, "search_async"):
            raw = await retriever.search_async(req.max_results)
        else:
            raw = retriever.search(req.max_results)
    except HTTPException:
        _count(STATS.record_search, req.retriever, _elapsed_ms(started), 0, "HTTPException")
        raise
    except Exception as e:
        logger.warning("search failed (retriever=%s): %s", req.retriever, e)
        _count(STATS.record_search, req.retriever, _elapsed_ms(started), 0, type(e).__name__)
        raise HTTPException(status_code=502, detail=f"search failed: {e}")
    elapsed_ms = _elapsed_ms(started)

    results = []
    dropped_no_url = 0
    for item in raw or []:
        if not isinstance(item, dict):
            dropped_no_url += 1
            continue
        url = item.get("href") or item.get("url") or ""
        if not url:
            # 没有 url 的结果无法归因、也无法被引用，只能计数后丢弃。
            dropped_no_url += 1
        raw_content = item.get("raw_content") or item.get("content") or ""
        results.append(SearchResultItem(
            title=item.get("title") or "",
            url=url,
            snippet=item.get("body") or item.get("content") or item.get("snippet") or "",
            content=raw_content[:SEARCH_CONTENT_CHAR_CAP] if raw_content else "",
        ))

    diagnostics = {
        "retriever": req.retriever,
        "elapsed_ms": elapsed_ms,
        "raw_count": len(raw or []),
        "distinct_urls": len({r.url for r in results if r.url}),
        "dropped_no_url": dropped_no_url,
    }
    _count(STATS.record_search, req.retriever, elapsed_ms, len(results), None)
    return SearchResponse(results=results, diagnostics=diagnostics)


@app.post("/scrape", response_model=ScrapeResponse)
async def scrape(req: ScrapeRequest, request: Request):
    """HTTP 入口：只负责 inflight 计数，抓取本体在 _scrape（异常也要归还计数）。"""
    _count(STATS.enter_scrape)
    try:
        return await _scrape(req, request)
    finally:
        _count(STATS.exit_scrape)


async def _scrape(req: ScrapeRequest, request: Request):
    from pycrawler.scraper.outcome import ScrapeOutcome
    from pycrawler.scraper.scraper import Scraper
    from pycrawler.utils.workers import WorkerPool

    request_id = request.headers.get(REQUEST_ID_HEADER, "")
    set_request_id(request_id)

    if not req.urls:
        return ScrapeResponse(contents=[], outcomes=[])

    pool = WorkerPool(max_workers=4)
    scraper = Scraper(
        urls=req.urls,
        user_agent=USER_AGENT,
        # 默认走主内容抽取：整页文本里通常一半以上是站点框架（导航/分类列表/推荐位），
        # 那些内容会把与问题无关的实体带进上下文。抽不出主内容的页按空正文交代原因。
        scraper=DEFAULT_SCRAPER_BACKEND,
        worker_pool=pool,
        request_id=request_id,
    )
    try:
        data = await scraper.run()
    except Exception as e:
        logger.warning("scrape failed: %s", e)
        raise HTTPException(status_code=502, detail=f"scrape failed: {e}")

    cap = req.max_chars or DEFAULT_SCRAPE_CHAR_CAP
    if cap < 0:
        cap = DEFAULT_SCRAPE_CHAR_CAP

    contents = []
    outcome_by_url = {}
    for item in data or []:
        url = item.get("url") or ""
        outcome = item.get("outcome")
        if outcome is None:
            # 后端没填 outcome 时该 URL 仍要出现在结果里，原因只能是 unknown，
            # 不能因为拿不到细节就让它从结果中消失。
            outcome = ScrapeOutcome(url=url, request_id=request_id)
        raw = item.get("raw_content") or ""
        if raw:
            outcome.truncated = len(raw) >= cap
            outcome.text_chars = len(raw)
            contents.append(ScrapedContent(
                url=url,
                title=item.get("title") or "",
                content=raw[:cap],  # 截断防爆响应（sourceDistill 模式由调用方调大）
            ))
        outcome_by_url[url] = outcome

    # 按"请求里的 URL"逐条产出（服务端内部的去重不改变这一条）：
    # 调用方请求过的每个 URL 都必须能查到自己的结果。
    per_url = [
        outcome_by_url.get(url)
        or ScrapeOutcome(url=url, request_id=request_id)
        for url in req.urls
    ]
    # 统计与响应取同一份对象列表 ⇒ 计数与调用方看到的结果不可能各说各话
    _count(STATS.record_scrape, per_url)
    return ScrapeResponse(contents=contents,
                          outcomes=[outcome.to_dict() for outcome in per_url])
