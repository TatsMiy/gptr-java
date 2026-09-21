"""ArXiv paper scraper —— 分级抓取（全文优先）。

修复说明：

原实现只调 `export.arxiv.org/api` 取 `paper.summary`（≈200 词），且该 API 在当前网络下
**两条路都不通**——经代理报 `ProxyError(RemoteDisconnected)`、直连报 429（限流）
→ 抓取恒为空 → 蒸馏仅 1-2 句 → 学术来源在素材池里被长文新闻（20+ 句）淹没
（实测 q08：9 个 arxiv 来源合计仅 9 条 note，而单篇网易新闻 23 条）。

实测可达性（容器内经代理）：
    arxiv.org/abs/<id>   200 / 1.5s /  42KB   ← 仅摘要
    arxiv.org/html/<id>  200 / 2.2s / 378KB   ← 全文（9 倍，2024+ 论文提供 HTML 版）
    arxiv.org/pdf/<id>   200 / 3.9s / 1.4MB   ← 全文
    export.arxiv.org/api 不通（代理拒绝 / 直连 429）

因此改为分级：HTML 全文 → PDF 全文 → abs 摘要页 → export API（最后兜底，防环境变化）。
每级失败都降级而非抛出，单个坏 URL 不能中断整条抓取管线（沿用原有容错契约）。
"""

from __future__ import annotations

import logging
import re
from typing import Any

import requests

logger = logging.getLogger(__name__)

_ID_RE = re.compile(
    r"(?:arxiv\.org/(?:abs|pdf|html)/)?(?P<id>\d{4}\.\d{4,5}(?:v\d+)?|[a-z\-]+/\d{7})(?:\.pdf)?",
    re.IGNORECASE,
)

_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")

# HTML 全文小于此长度视为无效（arxiv 对无 HTML 版的论文会返回提示页）
_MIN_USEFUL_CHARS = 5000


def _paper_id_from_link(link: str) -> str:
    """Extract an arXiv id from a URL or bare id string."""
    if not link:
        return ""
    m = _ID_RE.search(link.strip())
    if m:
        return m.group("id")
    # last path segment fallback (legacy behavior)
    return link.rstrip("/").split("/")[-1].removesuffix(".pdf")


class ArxivScraper:
    def __init__(self, link, session=None):
        self.link = link
        self.session = session

    # -- 各级抓取 ------------------------------------------------------

    @staticmethod
    def _get(url: str, timeout: int = 40):
        return requests.get(url, headers={"User-Agent": _UA}, timeout=timeout)

    @staticmethod
    def _html_to_text(html: str) -> str:
        """提取正文文本（优先 article/main，退化到全页）。"""
        try:
            from bs4 import BeautifulSoup
        except Exception:
            return ""
        soup = BeautifulSoup(html, "html.parser")
        for tag in soup(["script", "style", "nav", "footer", "header"]):
            tag.decompose()
        node = soup.find("article") or soup.find("main") or soup.body or soup
        text = node.get_text("\n", strip=True)
        return re.sub(r"\n{3,}", "\n\n", text)

    def _try_html(self, paper_id: str) -> str:
        html = self._get("https://arxiv.org/html/%s" % paper_id).text
        if not html or len(html) < _MIN_USEFUL_CHARS:
            return ""
        text = self._html_to_text(html)
        return text if len(text) >= _MIN_USEFUL_CHARS else ""

    def _try_pdf(self, paper_id: str) -> str:
        try:
            import fitz  # PyMuPDF
        except Exception:
            return ""
        try:
            resp = self._get("https://arxiv.org/pdf/%s" % paper_id, timeout=90)
            if resp.status_code != 200 or not resp.content:
                return ""
            with fitz.open(stream=resp.content, filetype="pdf") as doc:
                pages = [p.get_text() for p in doc]
            text = "\n".join(pages)
            return text if len(text) >= _MIN_USEFUL_CHARS else ""
        except Exception as e:
            logger.info("arxiv pdf fallback failed for %s: %s", paper_id, e)
            return ""

    def _try_abs(self, paper_id: str) -> tuple[str, str]:
        """摘要页 → (content, title)。"""
        try:
            html = self._get("https://arxiv.org/abs/%s" % paper_id).text
            from bs4 import BeautifulSoup
            soup = BeautifulSoup(html, "html.parser")
            title_node = soup.find("h1", class_="title")
            abs_node = soup.find("blockquote", class_="abstract")
            title = (title_node.get_text(" ", strip=True) if title_node else "").replace(
                "Title:", "").strip()
            abstract = (abs_node.get_text(" ", strip=True) if abs_node else "").replace(
                "Abstract:", "").strip()
            if abstract:
                return "Title: %s\nAbstract: %s" % (title or paper_id, abstract), title or paper_id
        except Exception as e:
            logger.info("arxiv abs fallback failed for %s: %s", paper_id, e)
        return "", ""

    def _try_api(self, paper_id: str) -> tuple[str, str]:
        """export API（原实现路径，当前网络下通常失败，保留兜底）。"""
        try:
            import arxiv
            client = arxiv.Client()
            paper = next(client.results(arxiv.Search(id_list=[paper_id], max_results=1)))
            authors = ", ".join(a.name for a in (paper.authors or []))
            published = paper.published.date().isoformat() if paper.published else ""
            title = paper.title or paper_id
            return ("Published: %s; Author: %s; Content: %s"
                    % (published, authors, paper.summary or "")), title
        except Exception as e:
            logger.info("arxiv api fallback failed for %s: %s", paper_id, e)
            return "", ""

    # -- 对外契约 ------------------------------------------------------

    def scrape(self):
        """按 HTML → PDF → abs → API 分级抓取，返回 (context, images, title)。

        全部失败 → 返回空（沿用原有降级契约，不抛异常）。
        """
        paper_id = _paper_id_from_link(self.link)
        if not paper_id:
            return "", [], ""
        images: list[Any] = []

        for name, fn in (("html", self._try_html), ("pdf", self._try_pdf)):
            text = fn(paper_id)
            if text:
                logger.info("arxiv %s: %s full text, %d chars", paper_id, name, len(text))
                return text, images, paper_id

        content, title = self._try_abs(paper_id)
        if content:
            logger.info("arxiv %s: abs page (no HTML/PDF), %d chars", paper_id, len(content))
            return content, images, title

        content, title = self._try_api(paper_id)
        if content:
            logger.info("arxiv %s: export api (last resort), %d chars", paper_id, len(content))
            return content, images, title
        return "", [], ""
