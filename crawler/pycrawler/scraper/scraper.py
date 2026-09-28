"""Web scraper module for GPT Researcher.

This module provides the Scraper class that extracts content from URLs
using various scraping backends (BeautifulSoup, PyMuPDF, Browser, etc.).
"""

import asyncio
import contextvars
import functools
import importlib
import inspect
import logging
import socket
import subprocess
import sys
import time
from urllib.parse import urlparse

import requests
from colorama import Fore, init

from pycrawler.scraper.outcome import (
    ScrapeOutcome,
    classify_page_kind,
    http_status_reason,
    mark_fetch_failure,
)
from pycrawler.utils.workers import WorkerPool
from pycrawler.utils.url_security import UnsafeURLError, validate_url

# 惰性后端注册表（module_path, class_name）：仅在选择到该后端时才 import，
# 避免顶层导入强依赖 selenium/zendriver/firecrawl/langchain-community 等可选包。
SCRAPER_BACKENDS = {
    "pdf": ("pycrawler.scraper.pymupdf.pymupdf", "PyMuPDFScraper"),
    "arxiv": ("pycrawler.scraper.arxiv.arxiv", "ArxivScraper"),
    "bs": ("pycrawler.scraper.beautiful_soup.beautiful_soup", "BeautifulSoupScraper"),
    "trafilatura": ("pycrawler.scraper.trafilatura.trafilatura_scraper", "TrafilaturaScraper"),
    "web_base_loader": ("pycrawler.scraper.web_base_loader.web_base_loader", "WebBaseLoaderScraper"),
    "browser": ("pycrawler.scraper.browser.browser", "BrowserScraper"),
    "nodriver": ("pycrawler.scraper.browser.nodriver_scraper", "NoDriverScraper"),
    "tavily_extract": ("pycrawler.scraper.tavily_extract.tavily_extract", "TavilyExtract"),
    "firecrawl": ("pycrawler.scraper.firecrawl.firecrawl", "FireCrawl"),
}


def _load_backend(key: str):
    """按 key 惰性导入后端类；缺依赖时抛 ImportError（由调用方决定兜底）。"""
    module_path, class_name = SCRAPER_BACKENDS[key]
    module = importlib.import_module(module_path)
    return getattr(module, class_name)

# Known anti-bot/challenge-page markers, case-insensitive substring match.
# These pages return HTTP 200 with real (often large) HTML bodies, so
# neither an exception nor the short-content check below catches them --
# without this, a block/challenge page is ingested as if it were the
# article's real content. Each marker is anchored specifically enough to
# avoid colliding with ordinary prose (e.g. "researchgate - temporarily
# unavailable", not the bare phrase "temporarily unavailable", which a
# legitimate article about an unrelated outage could plausibly contain).
# Not exhaustive; add more as new blockers are observed in practice.
_BLOCK_PAGE_MARKERS = (
    "anubis uses a proof-of-work scheme",  # HAL and other Anubis-fronted sites
    "making sure you're not a bot",
    "checking your browser before accessing",
    "enable javascript and cookies to continue",
    "researchgate - temporarily unavailable",  # ResearchGate's specific wording
    "please verify you are a human",
    "attention required! | cloudflare",
    "sorry, you have been blocked",
)

# Block pages are the entire response body -- a "please wait" message, not
# a real article -- so they're always short and always appear at the very
# start of the content. Checking only a prefix avoids lower-casing and
# scanning multi-megabyte legitimate documents on every scrape.
_BLOCK_PAGE_CHECK_PREFIX_LEN = 5_000

# Word-list/vocab dumps (plain lists of unrelated words, no prose) scrape
# cleanly and can dominate a report's context, since they lexically match
# almost any query. They have essentially zero sentence-ending punctuation
# relative to their size, unlike any real prose (even dense technical
# writing has a period every ~100-200 characters). Size alone isn't used as
# a signal -- long legitimate documents exist -- only the near-total absence
# of sentence structure combined with real size is checked, to keep the
# false-positive rate on real content low. Includes CJK fullwidth sentence
# terminators (。！？) alongside the Latin ones, so long-form Chinese/
# Japanese/Korean prose -- which never uses "." "!" "?" -- isn't
# misclassified as a word list purely for lacking ASCII punctuation.
_MIN_LENGTH_FOR_WORDLIST_CHECK = 200_000
_MAX_SENTENCE_DENSITY = 1 / 5000  # at most 1 sentence-ending mark per 5000 chars
_SENTENCE_ENDING_CHARS = frozenset(".!?。！？")


def _looks_like_block_page(text: str) -> bool:
    prefix = text[:_BLOCK_PAGE_CHECK_PREFIX_LEN].lower()
    return any(marker in prefix for marker in _BLOCK_PAGE_MARKERS)


def _looks_like_word_list(text: str) -> bool:
    if len(text) < _MIN_LENGTH_FOR_WORDLIST_CHECK:
        return False
    sentence_endings = sum(1 for ch in text if ch in _SENTENCE_ENDING_CHARS)
    return (sentence_endings / len(text)) < _MAX_SENTENCE_DENSITY


# get_scraper() picks a backend from the URL's suffix, but PDFs served from
# institutional repositories (DSpace/EPrints-style download endpoints) are
# routed by content type, not a file extension -- e.g.
# "scholarspace.manoa.hawaii.edu/bitstreams/<uuid>/download" has none. Those
# fall through to a text/HTML scraper, which has no way to decode the PDF
# body and returns its raw bytes (FlateDecode streams, xref tables, /Annot
# objects) as if it were the page's real text -- silently: no exception, a
# normal-looking content_length, just unusable content.
#
# These tokens are PDF's own internal structural syntax; they essentially
# never occur, even coincidentally, in real prose. Two independent hits is
# enough to be confident this is raw PDF, not text that happens to contain
# one of these words in isolation.
_PDF_STRUCTURE_MARKERS = ("endobj", "endstream", "/FlateDecode", "xref", "trailer")
_MIN_PDF_MARKER_HITS = 2


def _looks_like_unextracted_pdf(text: str) -> bool:
    if text.startswith("%PDF-"):
        return True
    hits = sum(1 for marker in _PDF_STRUCTURE_MARKERS if marker in text)
    return hits >= _MIN_PDF_MARKER_HITS


# Human-readable form of each rejection reason, kept identical to the wording the
# log used before reasons became codes, so existing log greps keep working.
_REJECT_MESSAGES = {
    "empty_content": "Content empty",
    "too_short": "Content too short",
    "block_challenge": "Anti-bot/challenge page detected",
    "word_list": "Word-list-like content detected",
    "pdf_unresolved": "Unextracted PDF could not be recovered",
}


def _accepts_outcome(scrape) -> bool:
    """Whether a backend's scrape() takes an outcome to fill.

    Only some backends were extended; the rest keep their original signature and
    leave the reason at "unknown", rather than failing on an unexpected keyword.
    """
    try:
        return "outcome" in inspect.signature(scrape).parameters
    except (TypeError, ValueError):
        return False


def _in_context(func):
    """Run ``func`` in a worker thread with the caller's context variables.

    A thread from the executor starts with an empty context, so the request id
    set for this call would be missing from every log line the backend emits --
    exactly the lines that say which URL failed and why.
    """
    context = contextvars.copy_context()
    return lambda: context.run(func)


class Scraper:
    """
    Scraper class to extract the content from the links
    """

    def __init__(self, urls, user_agent, scraper, worker_pool: WorkerPool, request_id=""):
        """
        Initialize the Scraper class.
        Args:
            urls: List of URLs to scrape (duplicates will be removed)
            request_id: Correlation id from the caller, copied onto every outcome
                so a crawler log line or result can be traced back to one call.
        """
        # Optimization: Remove duplicate URLs to avoid redundant scraping
        unique_urls = list(dict.fromkeys(urls))  # Preserves order while removing duplicates
        duplicates_removed = len(urls) - len(unique_urls)

        self.urls = unique_urls
        self.request_id = request_id
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": user_agent})
        self.scraper = scraper
        if self.scraper == "tavily_extract":
            self._check_pkg(self.scraper)
        if self.scraper == "firecrawl":
            self._check_pkg(self.scraper)
        self.logger = logging.getLogger(__name__)
        self.worker_pool = worker_pool

        # Log deduplication results if duplicates were found
        if duplicates_removed > 0:
            self.logger.info(
                f"Removed {duplicates_removed} duplicate URL(s). "
                f"Scraping {len(unique_urls)} unique URLs instead of {len(urls)}."
            )

    async def run(self):
        """
        Extracts the content from the links
        """
        contents = await asyncio.gather(
            *(self.extract_data_from_url(url, self.session) for url in self.urls)
        )

        # Every row is kept, including the ones that yielded no content: the row
        # is what carries the reason, so filtering on raw_content here is exactly
        # what would erase that reason again.
        res = [content for content in contents if isinstance(content, dict)]
        return res

    def _check_pkg(self, scrapper_name: str) -> None:
        """
        Checks and ensures required Python packages are available for scrapers that need
        dependencies beyond requirements.txt. When adding a new scraper to the repo, update `pkg_map`
        with its required information and call check_pkg() during initialization.
        """
        pkg_map = {
            "tavily_extract": {
                "package_installation_name": "tavily-python",
                "import_name": "tavily",
            },
            "firecrawl": {
                "package_installation_name": "firecrawl-py",
                "import_name": "firecrawl",
            },
        }
        pkg = pkg_map[scrapper_name]
        if not importlib.util.find_spec(pkg["import_name"]):
            pkg_inst_name = pkg["package_installation_name"]
            init(autoreset=True)
            print(Fore.YELLOW + f"{pkg_inst_name} not found. Attempting to install...")
            try:
                subprocess.check_call(
                    [sys.executable, "-m", "pip", "install", pkg_inst_name]
                )
                importlib.invalidate_caches()
                print(Fore.GREEN + f"{pkg_inst_name} installed successfully.")
            except subprocess.CalledProcessError:
                raise ImportError(
                    Fore.RED
                    + f"Unable to install {pkg_inst_name}. Please install manually with "
                    f"`pip install -U {pkg_inst_name}`"
                )

    async def extract_data_from_url(self, link, session):
        """
        Extracts the data from the link with logging
        """
        outcome = ScrapeOutcome(url=link, request_id=self.request_id)
        async with self.worker_pool.throttle():
            started = time.monotonic()
            try:
                # Reject SSRF / local-file targets (internal hosts, cloud metadata
                # endpoints, file:// paths, etc.) before any request is made.
                try:
                    validate_url(link)
                except UnsafeURLError as e:
                    self.logger.warning(f"Skipping unsafe URL {link}: {e}")
                    # 同一个异常类型覆盖两类成因，靠异常链区分：主机名解析失败是
                    # 传输层问题（与 _fetch 的连不上同类），其余才是本地策略拒绝。
                    unresolvable = isinstance(e.__cause__, socket.gaierror)
                    mark_fetch_failure(outcome, "network_error" if unresolvable else "unsafe_url")
                    return self._result(link, "", [], "", outcome)

                Scraper = self.get_scraper(link)
                scraper = Scraper(link, session)

                # Get scraper name
                scraper_name = scraper.__class__.__name__
                self.logger.info(f"\n=== Using {scraper_name} ===")

                # Get content
                if hasattr(scraper, "scrape_async"):
                    content, image_urls, title = await scraper.scrape_async()
                else:
                    scrape = scraper.scrape
                    if _accepts_outcome(scrape):
                        scrape = functools.partial(scrape, outcome=outcome)
                    (
                        content,
                        image_urls,
                        title,
                    ) = await asyncio.get_running_loop().run_in_executor(
                        self.worker_pool.executor, _in_context(scrape)
                    )

                # Backends that do not fill an outcome still report a length, so
                # the page kind below is not decided on a stale zero.
                if content and not outcome.text_chars:
                    outcome.text_chars = len(content)

                outcome.page_kind = classify_page_kind(
                    http_status=outcome.http_status,
                    has_password_form=outcome.has_password_form,
                    text_chars=outcome.text_chars,
                )

                if not content:
                    return self._reject(link, title, "empty_content", outcome)
                if len(content) < 100:
                    return self._reject(link, title, "too_short", outcome)

                # Log results
                self.logger.info(f"\nTitle: {title}")
                self.logger.info(f"Content length: {len(content)} characters")
                self.logger.info(f"Number of images: {len(image_urls)}")
                self.logger.info(f"URL: {link}")
                self.logger.info("=" * 50)

                if _looks_like_block_page(content):
                    return self._reject(link, title, "block_challenge", outcome)

                if _looks_like_word_list(content):
                    return self._reject(link, title, "word_list", outcome)

                # A page whose whole point is to ask for credentials is not the
                # article, however much text it carries -- a membership page reads
                # as ordinary prose and passes every content-length check.
                if outcome.page_kind == "login_or_wall":
                    return self._reject(link, title, "block_challenge", outcome)

                if outcome.page_kind == "error_page" and outcome.http_status is not None:
                    return self._reject(
                        link, title, http_status_reason(outcome.http_status), outcome
                    )

                if _looks_like_unextracted_pdf(content) and _load_backend("pdf") is not Scraper:
                    self.logger.warning(
                        f"{link} looks like unextracted PDF binary via {scraper_name} "
                        f"(no .pdf suffix, so get_scraper() didn't route it to "
                        f"PyMuPDFScraper) -- retrying with PyMuPDFScraper"
                    )
                    retried = await self._retry_as_pdf(link, session, outcome)
                    if retried is not None:
                        return retried
                    self.logger.warning(f"PyMuPDFScraper retry also failed for {link}")
                    return self._reject(link, title, "pdf_unresolved", outcome, stage="pdf_retry")

                outcome.reason = "ok"
                outcome.stage = "quality"
                return self._result(link, content, image_urls, title, outcome)

            except Exception as e:
                self.logger.error(f"Error processing {link}: {str(e)}")
                mark_fetch_failure(outcome, "exception", stage="exception")
                return self._result(link, "", [], "", outcome)
            finally:
                # Stamped after the row is built; the row holds this same outcome
                # object, so the value is present by the time a caller reads it.
                outcome.elapsed_ms = int((time.monotonic() - started) * 1000)

    def _result(self, link, content, image_urls, title, outcome):
        """One row per requested URL. The row survives even when it carries no
        content, because the row is what carries the reason."""
        return {
            "url": link,
            "raw_content": content or None,
            "image_urls": image_urls,
            "title": title,
            "outcome": outcome,
        }

    def _reject(self, link, title, reason, outcome, stage="quality"):
        """Treat a fetched-but-unusable page as a scrape failure: no content in
        the row, but a reason that reaches the caller instead of only the log."""
        self.logger.warning(
            f"{_REJECT_MESSAGES.get(reason, reason)} for {link}, treating as fetch failure"
        )
        mark_fetch_failure(outcome, reason, stage=stage)
        return self._result(link, "", [], title, outcome)


    async def _retry_as_pdf(self, link, session, outcome):
        """Re-fetch link with PyMuPDFScraper after the scraper get_scraper()
        originally picked returned unextracted PDF binary.

        The retry fills the same outcome, so on success the row reports the
        backend that actually produced the content and the run is marked as
        degraded rather than clean.

        Returns the normal extract_data_from_url result dict on success, or
        None if the retry also failed to produce usable content (so the
        caller falls back to treating this as an ordinary fetch failure
        instead of passing binary through).
        """
        scraper = _load_backend("pdf")(link, session)
        content, image_urls, title = await asyncio.get_running_loop().run_in_executor(
            self.worker_pool.executor,
            _in_context(functools.partial(scraper.scrape, outcome=outcome)),
        )
        if not content or len(content) < 100:
            return None
        self.logger.info(f"PyMuPDFScraper retry recovered {len(content)} characters for {link}")
        # The page was obtained, but only after the backend chosen for it failed:
        # that is a degraded success, and it is recorded as one instead of being
        # folded into the failure reason.
        outcome.degraded = True
        outcome.resolved_by = "pdf_retry"
        outcome.reason = "ok"
        outcome.stage = "pdf_retry"
        return self._result(link, content, image_urls, title, outcome)

    def get_scraper(self, link):
        """
        The function `get_scraper` determines the appropriate scraper class based on the provided link
        or a default scraper if none matches.

        Args:
          link: The `get_scraper` method takes a `link` parameter which is a URL link to a webpage or a
        PDF file. Based on the type of content the link points to, the method determines the appropriate
        scraper class to use for extracting data from that content.

        Returns:
          The `get_scraper` method returns the scraper class based on the provided link. The method
        checks the link to determine the appropriate scraper class to use based on predefined mappings
        in the `SCRAPER_CLASSES` dictionary. If the link ends with ".pdf", it selects the
        `PyMuPDFScraper` class. If the link contains "arxiv.org", it selects the `ArxivScraper
        """

        scraper_key = None

        # Inspect only the path component so query strings / fragments don't
        # hide the extension (e.g. signed CDN/S3 links like "…/doc.pdf?sig=…").
        # Match case-insensitively because ".PDF" is a perfectly valid suffix.
        path = urlparse(link).path
        if path.lower().endswith(".pdf"):
            scraper_key = "pdf"
        elif "arxiv.org" in link:
            scraper_key = "arxiv"
        else:
            scraper_key = self.scraper

        if scraper_key not in SCRAPER_BACKENDS:
            raise Exception("Scraper not found.")

        # 惰性导入：缺可选依赖（selenium/firecrawl/langchain 等）时抛 ImportError，
        # 由上层（api.py）决定兜底，而非整个 scraper 包导入即崩。
        return _load_backend(scraper_key)
