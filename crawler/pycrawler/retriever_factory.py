"""检索器工厂（自 gpt-researcher 提取精简，Apache-2.0）。

get_retriever(name) 按名惰性返回检索器类——每个引擎的依赖（tavily/exa/ddgs…）
只在选中该引擎时才 import，避免整包导入拉全部重型可选依赖。
mcp 检索器已裁剪（依赖 llm_provider 与 researcher 注入，非无状态壳所需）。
"""

# 引擎名 → (模块路径, 类名)
_RETRIEVER_MODULES = {
    "google": ("pycrawler.retrievers.google.google", "GoogleSearch"),
    "searx": ("pycrawler.retrievers.searx.searx", "SearxSearch"),
    "searchapi": ("pycrawler.retrievers.searchapi.searchapi", "SearchApiSearch"),
    "serpapi": ("pycrawler.retrievers.serpapi.serpapi", "SerpApiSearch"),
    "serper": ("pycrawler.retrievers.serper.serper", "SerperSearch"),
    "duckduckgo": ("pycrawler.retrievers.duckduckgo.duckduckgo", "Duckduckgo"),
    "bing": ("pycrawler.retrievers.bing.bing", "BingSearch"),
    "brave": ("pycrawler.retrievers.brave.brave", "BraveSearch"),
    "bocha": ("pycrawler.retrievers.bocha.bocha", "BoChaSearch"),
    "arxiv": ("pycrawler.retrievers.arxiv.arxiv", "ArxivSearch"),
    "tavily": ("pycrawler.retrievers.tavily.tavily_search", "TavilySearch"),
    "groundroute": ("pycrawler.retrievers.groundroute.groundroute", "GroundRouteSearch"),
    "exa": ("pycrawler.retrievers.exa.exa", "ExaSearch"),
    "crw": ("pycrawler.retrievers.crw.crw", "CRWRetriever"),
    "semantic_scholar": ("pycrawler.retrievers.semantic_scholar.semantic_scholar", "SemanticScholarSearch"),
    "pubmed_central": ("pycrawler.retrievers.pubmed_central.pubmed_central", "PubMedCentralSearch"),
    "custom": ("pycrawler.retrievers.custom.custom", "CustomRetriever"),
    "xquik": ("pycrawler.retrievers.xquik.xquik", "XquikSearch"),
    "openalex": ("pycrawler.retrievers.openalex.openalex", "OpenAlexSearch"),
    "getxapi": ("pycrawler.retrievers.getxapi.getxapi", "GetXAPISearch"),
}


def retriever_names() -> tuple:
    """Names this factory can build.

    The counting layer uses it to keep a request-supplied string out of its
    labels: an unknown name is pooled rather than becoming a key of its own.
    """
    return tuple(_RETRIEVER_MODULES)


def get_retriever(retriever: str):
    """按名返回检索器类；未知名字返回 None。

    Args:
        retriever: 检索器名（duckduckgo/arxiv/bocha/tavily/serper/...）。
    """
    entry = _RETRIEVER_MODULES.get(retriever)
    if entry is None:
        return None
    module_path, class_name = entry
    import importlib

    module = importlib.import_module(module_path)
    return getattr(module, class_name)
