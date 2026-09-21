"""Shared contract for retrievers.

Retrievers fall into two kinds, and the difference matters:

* most return **URLs that still need scraping** — the search API gives back a
  link plus a short snippet, and the real page text is fetched later;
* a few return **content they already fetched** — PubMed Central hands back
  full article text, and a custom retriever's documented contract is
  ``list[{url, raw_content}]``.

Historically nothing recorded which kind a retriever was, so
``_search_relevant_source_urls`` inferred it from the length of any
``raw_content`` field::

    if url and raw_content and len(raw_content) > 100:

That guess is wrong whenever a snippet happens to exceed the threshold: the
result is treated as already-fetched, its URL is never scraped, and the report
ends up with no verifiable citation for it (#1846, #1892). The workaround was
to cap snippet lengths inside individual retrievers so they stayed under 100
characters -- per-retriever tuning to satisfy a heuristic.

``requires_scraping`` replaces the guess with a declaration. It defaults to
``True``, which is the correct answer for the large majority of retrievers.

Declaring is optional. A retriever that does not define ``requires_scraping``
-- including any third-party or user-defined one -- keeps the legacy
length-based behaviour exactly, so nothing outside this repository has to
change.
"""
import os
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional


class BaseRetriever(ABC):
    """Optional base class documenting the retriever contract.

    Subclassing is not required; ``_search_relevant_source_urls`` only looks
    for a ``requires_scraping`` attribute. Inheriting simply makes the
    declaration explicit and gives new retrievers a place to read the contract.
    """

    #: Whether results are URLs that still need fetching (``True``, the
    #: default) or content the retriever has already retrieved (``False``).
    #:
    #: Override as a plain class attribute for a fixed answer, or as a
    #: ``property`` when it depends on how the retriever was configured.
    requires_scraping: bool = True

    @abstractmethod
    def search(self, max_results: int = 7) -> List[Dict[str, Any]]:
        """Return search results.

        With ``requires_scraping = True`` each item should carry a URL under
        ``url`` or ``href``; any ``body``/``snippet`` is treated as a preview
        and the page is scraped for its real content.

        With ``requires_scraping = False`` each item should carry a URL and the
        already-retrieved text under ``raw_content``; no scraping is performed.
        """
        raise NotImplementedError


def resolve_api_key(headers, header_name, env_name, *, required=True):
    """检索器 API key 解析（2026-09-19）：**请求 header 优先 → 环境变量回落**。


    * header 由 Java worker 按"当前检索器"注入（观测台配置 → 请求 header），**改配置无需重建容器**；
    * env 保留为既有部署的回落路径（``docker-compose.yml`` / ``.env``），故存量部署零破坏；
    * ``required=False`` 时缺失返回 ``None``（如 PubMed Central 的 NCBI key 本就可选）。

        20 个检索器的 key 读取只允许经此一处实现，不得各写各的。
    """
    if headers:
        v = headers.get(header_name)
        if v:
            return v
    v = os.environ.get(env_name)
    if v:
        return v
    if required:
        raise ValueError(
            f"{env_name} not set: configure it in the dashboard "
            f"(config key retriever.*.api-key) or set the {env_name} environment variable")
    return None
