"""请求/响应 DTO（Pydantic）。"""
from typing import List, Optional
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
    contents: List[ScrapedContent]
