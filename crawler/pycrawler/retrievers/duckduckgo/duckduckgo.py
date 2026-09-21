"""DuckDuckGo 检索器（自实现 HTML 端点直取，2026-09-10 起替代 ddgs 库调用）。

为什么不用 ddgs 库（实测依据）：
  - 国内直连 duckduckgo.com 被 DNS 污染（解析到 Meta/Dropbox IP）→ 必须走代理；
  - 走代理后 **ddgs 库（primp/HTTP-2，多后端聚合）不稳定**：`peer closed connection
    without sending TLS close_notify` 反复出现，html 后端成功率仅约 60%、每次 11–17s；
  - 同一条链路改用 **requests（HTTP/1.1）直接抓 html.duckduckgo.com** → 稳定、2–3s；
  - ddgs/primp 均不提供 HTTP/2 降级开关（`http2`/`http1` 参数都不被接受）。
因此这里直接请求 HTML 端点并解析结果块，HTTP/1.1、单后端、超时可控。

兼容性：本类仍暴露 `requires_scraping` 与 `search(max_results)` 契约（api.py 依赖）。
gpt-researcher 的 `_MAX_PREFETCHED_LEN` 说明保留：snippet 截到 100 字符，避免上游把
"已有正文"误判为已抓取而不去真抓页面。
"""
import os
from urllib.parse import parse_qs, unquote, urlparse

import requests
from bs4 import BeautifulSoup

# gpt_researcher.skills.researcher._search_relevant_source_urls() treats any search result
# whose raw_content/body exceeds 100 characters as already-fetched full text. ddgs/DDG put an
# ordinary snippet in "body"; capped here so the real page still gets scraped (otherwise
# get_source_urls() returns [] and reports ship with zero verifiable citations).
_MAX_PREFETCHED_LEN = 100

_HTML_ENDPOINT = "https://html.duckduckgo.com/html/"
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")


def _looks_like_challenge(html: str) -> bool:
    """识别 DDG 的反爬/人机验证页（2026-09-10 实测形态）。"""
    head = html[:20000].lower()
    return ("select all squares" in head
            or "anomaly-modal" in head
            or "unfortunately, bots use duckduckgo" in head
            or "image-check_" in head)


def _resolve_href(href: str) -> str:
    """DDG 结果链接是 /l/?uddg=<encoded> 形式的重定向，取出真实 URL。"""
    if not href:
        return ""
    if href.startswith("//"):
        href = "https:" + href
    if "uddg=" in href:
        try:
            qs = parse_qs(urlparse(href).query)
            real = qs.get("uddg") or qs.get("u")
            if real:
                return unquote(real[0])
        except Exception:
            pass
    return href


class Duckduckgo:
    """DuckDuckGo HTML 端点检索器（零 key，走 DDG_PROXY/HTTPS_PROXY/HTTP_PROXY 代理）。"""

    # DDG 只给 snippet；正文仍须真抓。
    requires_scraping = True

    def __init__(self, query, query_domains=None, headers=None):
        self.query = query
        self.query_domains = query_domains or None
        proxy = (os.environ.get("DDG_PROXY") or os.environ.get("HTTPS_PROXY")
                 or os.environ.get("HTTP_PROXY"))
        self.proxies = {"http": proxy, "https": proxy} if proxy else None
        try:
            self.timeout = max(5, int(os.environ.get("DDG_TIMEOUT", "30")))
        except ValueError:
            self.timeout = 30

    def search(self, max_results=5):
        """抓 HTML 结果页并解析为 {href, body, title?} 列表。

        失败**不吞异常**：向上抛给 api.py → 502 → Java 弹性层（Retry + 熔断 + 降级链）。
        真正的"零结果"（页面正常但无结果块）仍返回 []。
        """
        try:
            resp = requests.get(
                _HTML_ENDPOINT,
                params={"q": self.query, "kl": "wt-wt"},
                headers={"User-Agent": _UA, "Accept-Language": "en-US,en;q=0.9"},
                proxies=self.proxies,
                timeout=self.timeout,
            )
            resp.raise_for_status()
        except Exception as e:
            # 不吞异常（2026-09-10）：失败必须可见，否则短路整条弹性链（旧 ddgs 实现的教训）。
            raise RuntimeError(
                f"duckduckgo search failed: {type(e).__name__}: {e}") from e

        # 人机验证/反爬挑战页检测（2026-09-10）：DDG 对「代理 IP + 自动化请求」会返回
        # HTTP 202 + 图片验证码页（"Select all squares containing a duck"），页面无结果块。
        # **必须与"真的零结果"区分**——否则又是一次静默失败（返回 [] → Java 不重试 → 素材缺失）。
        if resp.status_code != 200 or _looks_like_challenge(resp.text):
            raise RuntimeError(
                "duckduckgo blocked: captcha/anomaly challenge (status=%s, len=%d) — "
                "代理出口 IP 被反爬拦截，需换检索源或换出口"
                % (resp.status_code, len(resp.text)))

        soup = BeautifulSoup(resp.text, "html.parser")
        out = []
        for block in soup.select("div.result, div.web-result"):
            a = block.select_one("a.result__a")
            if a is None:
                continue
            href = _resolve_href(a.get("href", ""))
            if not href:
                continue
            sn = block.select_one("a.result__snippet, div.result__snippet")
            body = sn.get_text(" ", strip=True) if sn is not None else ""
            item = {"href": href, "body": body[:_MAX_PREFETCHED_LEN]}
            title = a.get_text(" ", strip=True)
            if title:
                item["title"] = title
            out.append(item)
            if len(out) >= max_results:
                break
        return out
