import logging
import os
from typing import Dict, List
from urllib.parse import urljoin

import requests

logger = logging.getLogger(__name__)

# gpt_researcher.skills.researcher._search_relevant_source_urls() treats
# any search result whose raw_content/body exceeds 100 characters as
# already-fetched full text -- a heuristic meant for retrievers (e.g.
# PubMed Central) that genuinely return full article text inline. SearxNG
# populates "body" with an ordinary search-result snippet, which routinely
# exceeds 100 characters, so without this cap every result is wrongly
# treated as already-fetched and the real page is never actually scraped
# (get_source_urls() then returns [] and reports ship with zero verifiable
# citations). Capped here, at the source, rather than patched in
# _search_relevant_source_urls() itself, so any future upstream change to
# that function's classification logic (bug fixes, new bookkeeping) is
# inherited automatically. The truncated snippet is only ever used for
# that classification decision and early-stage sub-query planning -- once
# a URL takes the real scrape path, gpt-researcher fetches and uses the
# actual page content, not this snippet.
_MAX_PREFETCHED_LEN = 100

# SearxNG 的引擎可用性由**实例的出口 IP** 决定（同一份引擎名单，换个出口就是另一组能用），
# 所以"问哪些引擎、用哪种语言"是**部署级**参数，与实例地址同层（容器 env），不是任务级参数。
# 留空 = 不发送该参数，沿用实例默认。
_ENGINES_ENV = "SEARX_ENGINES"
_LANGUAGE_ENV = "SEARX_LANGUAGE"
_TIMEOUT_ENV = "SEARX_TIMEOUT"
_DEFAULT_TIMEOUT_S = 30
_MIN_TIMEOUT_S = 5

# 失败消息里最多列几个引擎：实例可能一屏几十个引擎全挂，日志要有界。
_MAX_REPORTED_ENGINES = 5


def _env_list(name: str) -> List[str]:
    """把 `a, b ,c` 解析成 ['a','b','c']；未设或全空白 → []（调用方据此决定不发该参数）。"""
    raw = os.environ.get(name, "")
    return [item.strip() for item in raw.split(",") if item.strip()]


def _env_timeout_s() -> int:
    """读超时秒数：非数字回落默认，过小抬到下限（与 duckduckgo 的 DDG_TIMEOUT 同口径）。"""
    try:
        return max(_MIN_TIMEOUT_S, int(os.environ.get(_TIMEOUT_ENV, _DEFAULT_TIMEOUT_S)))
    except (TypeError, ValueError):
        return _DEFAULT_TIMEOUT_S


def _unresponsive_engines(raw) -> List[str]:
    """把 SearxNG 的 `unresponsive_engines` 归成 `引擎(原因)` 文本。

    该字段形态是 `[[引擎名, 原因], ...]`，原因串直接写着 CAPTCHA / too many requests /
    access denied / timeout 这类事实——它是上游**免费送上来的失败诊断**，不能丢。
    形态不符（非列表、元素不是二元组）时按元素自身转字符串，宁可粗糙也不静默。
    """
    out = []
    for entry in raw or []:
        if isinstance(entry, (list, tuple)) and len(entry) >= 2:
            out.append("%s(%s)" % (entry[0], entry[1]))
        elif entry:
            out.append(str(entry))
    return out


class SearxSearch():
    """
    SearxNG API Retriever
    """

    # SearxNG puts an ordinary result snippet in "content"/"body"; the real
    # page text still has to be fetched.
    requires_scraping = True

    def __init__(self, query: str, query_domains=None, headers=None):
        """
        Initializes the SearxSearch object
        Args:
            query: Search query string
        """
        self.query = query
        self.query_domains = query_domains or None
        self.base_url = self.get_searxng_url()
        self.engines = _env_list(_ENGINES_ENV)
        self.language = os.environ.get(_LANGUAGE_ENV, "").strip()
        self.timeout = _env_timeout_s()

    def get_searxng_url(self) -> str:
        """
        Gets the SearxNG instance URL from environment variables
        Returns:
            str: Base URL of SearxNG instance
        """
        try:
            base_url = os.environ["SEARX_URL"]
            if not base_url.endswith('/'):
                base_url += '/'
            return base_url
        except KeyError:
            raise Exception(
                "SearxNG URL not found. Please set the SEARX_URL environment variable. "
                "You can find public instances at https://searx.space/"
            )

    def request_params(self) -> Dict[str, str]:
        """本次请求的查询参数：`q` + `format`，外加**只有当 env 给了值时才发**的两个参数。

        · `engines`：不传时实例按**默认引擎集**作答，实测整批查询只有 1 个引擎贡献结果
          （其余要么限流要么弹验证码），引擎冗余为零；显式点名才拿得到其余引擎。
        · `language`：不传时实例用自身默认；显式声明才能影响"来源多样性"（中文站/英文站配比）。
        """
        params = {
            # The search query.
            'q': self.query,
            # Output format of results. Format needs to be activated in searxng config.
            'format': 'json'
        }
        if self.engines:
            params['engines'] = ','.join(self.engines)
        if self.language:
            params['language'] = self.language
        return params

    def search(self, max_results: int = 10) -> List[Dict[str, str]]:
        """
        Searches the query using SearxNG API
        Args:
            max_results: Maximum number of results to return
        Returns:
            List of dictionaries containing search results

        失败**不吞异常**：向上抛给 api.py → 502 → Java 弹性层（Retry + 熔断 + 降级链）。
        关键分支是"**零结果但引擎全挂**"：SearxNG 会把失败诊断放在 `unresponsive_engines` 里，
        若不读它，全引擎被拦与"该查询真的没有结果"在调用方完全同形（静默丢素材）。
        真正的"零结果"（没有任何引擎报错）仍返回 []。
        """
        search_url = urljoin(self.base_url, "search")
        # TODO: Add support for query domains
        try:
            response = requests.get(
                search_url,
                params=self.request_params(),
                headers={'Accept': 'application/json'},
                # 必须带超时：实例 hung 住时无超时会挂死调用方连接。
                timeout=self.timeout,
            )
            response.raise_for_status()
        except requests.exceptions.RequestException as e:
            raise RuntimeError("searx search failed: %s: %s" % (type(e).__name__, e)) from e

        try:
            results = response.json()
        except ValueError as e:
            raise RuntimeError("searx response not json: %s" % e) from e

        if not isinstance(results, dict):
            raise RuntimeError(
                "searx response is not an object: %s" % type(results).__name__)

        raw_results = results.get('results', [])
        if not isinstance(raw_results, list):
            raise RuntimeError(
                "searx results is not a list: %s" % type(raw_results).__name__)

        search_response = []
        for result in raw_results:
            if not isinstance(result, dict):
                continue
            href = result.get('url') or result.get('href') or ''
            if not href:
                continue
            body = result.get('content') or result.get('snippet') or ''
            search_response.append({
                "href": href,
                "body": body[:_MAX_PREFETCHED_LEN],
            })
            if len(search_response) >= max_results:
                break

        down = _unresponsive_engines(results.get('unresponsive_engines'))
        if not search_response:
            if down:
                raise RuntimeError(
                    "searx no results and all/most engines unresponsive: %s"
                    % ", ".join(down[:_MAX_REPORTED_ENGINES])[:300])
            return []
        if down:
            # 有结果但部分引擎挂了：失败要可见，但不该把这次查询判死。
            logger.warning("searx partial engines unresponsive: %s",
                           ", ".join(down[:_MAX_REPORTED_ENGINES]))
        return search_response
