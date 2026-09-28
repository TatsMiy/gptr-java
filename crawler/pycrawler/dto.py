"""请求/响应 DTO（Pydantic）。"""
from typing import Any, Dict, List, Optional
from pydantic import BaseModel


class SearchRequest(BaseModel):
    query: str
    max_results: int = 5
    retriever: str = "duckduckgo"  # 零 key 默认；可选 tavily/serper/brave/...（见 get_retriever）


class SearchResultItem(BaseModel):
    title: str = ""
    url: str = ""
    snippet: str = ""
    content: str = ""  # raw_content 透传（全文型检索器如 pubmed_central/custom；无则空）


class SearchResponse(BaseModel):
    results: List[SearchResultItem]
    # 检索过程事实（retriever/耗时/原始条数/去重后条数/无 url 被丢弃条数）。
    # 每元素字段见 api.py 的 _search_diagnostics；调用方不消费也可忽略。
    diagnostics: Optional[Dict[str, Any]] = None


class ScrapeRequest(BaseModel):
    urls: List[str]
    # 可选：每 URL 正文返回上限（字符）。None=默认 4000（防响应过大）；
    # 引擎侧 sourceDistill 模式传更大值（如 20000），供 LLM 提炼覆盖全文。
    max_chars: Optional[int] = None


class ScrapedContent(BaseModel):
    url: str = ""
    title: str = ""
    content: str = ""  # 清洗后正文（默认截断 4000 字符；max_chars 可调大）


class ScrapeResponse(BaseModel):
    # 可用正文列表：语义与字段名保持不变，旧调用方无需改动。
    contents: List[ScrapedContent]
    # 逐 URL 结果与原因（含未进入 contents 的那些）。字段由
    # pycrawler.scraper.outcome.ScrapeOutcome 定义，此处只声明为 JSON 对象，
    # 以免同一份字段清单出现两处声明。
    outcomes: List[Dict[str, Any]] = []
