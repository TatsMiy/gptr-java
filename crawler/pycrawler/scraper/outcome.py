"""Per-URL scrape outcome: what was obtained, and why when nothing usable was.

Callers count and aggregate these, so ``reason`` / ``page_kind`` / ``stage`` are
closed vocabularies defined here. The raw structural features are carried
alongside the verdict so a caller can always overrule the ``page_kind``
heuristic with its own rule.
"""
from dataclasses import asdict, dataclass
import re

# Why a URL did not yield usable content. "ok" means it did. "unknown" is what a
# backend that does not fill an outcome leaves behind, so it is part of the
# vocabulary rather than an accidental value.
REASONS = (
    "ok",
    "http_4xx",
    "http_5xx",
    "timeout",
    "network_error",
    "too_large",
    "no_session",
    "empty_content",
    "too_short",
    "block_challenge",
    "word_list",
    "pdf_unresolved",
    "unsafe_url",
    "exception",
    "unknown",
)

# What kind of page the fetched document looks like. Two values the classifier
# never produces are kept in the vocabulary because a caller can assign them from
# knowledge this layer does not have: "download_or_resource" needs the URL shape,
# "listing" needs a comparison across pages of the same site.
PAGE_KINDS = (
    "article",
    "listing",
    "login_or_wall",
    "download_or_resource",
    "error_page",
    "unknown",
)

# Which step of the pipeline reached that conclusion.
STAGES = ("fetch", "parse", "quality", "pdf_retry", "exception")

# Shortest text still counted as an article. The boundary is deliberately low: it
# separates "there is a document here" from "there is nothing", not good writing
# from bad. Structural signals only -- a content-keyword test misfires on real
# articles whose own text contains the words a challenge page uses.
ARTICLE_MIN_CHARS = 500

# HTTP status class boundaries.
CLIENT_ERROR_STATUS = 400
SERVER_ERROR_STATUS = 500

# Reasons that describe a local refusal or local configuration rather than a
# failure of the remote site; callers keep them out of per-site failure rates.
NON_SITE_REASONS = frozenset({"too_large", "no_session", "unsafe_url"})


def http_status_reason(status: int) -> str:
    """Map an error status code onto the matching closed reason."""
    return "http_5xx" if status >= SERVER_ERROR_STATUS else "http_4xx"


def cjk_count(text: str) -> int:
    """Count CJK ideographs; a page with almost none is rarely the Chinese
    article that was asked for."""
    return sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")


# Hidden containers are how a sign-in modal is parked in the page for every
# visitor, readers who are already signed in included.
_HIDDEN_STYLE = re.compile(r"display\s*:\s*none|visibility\s*:\s*hidden", re.IGNORECASE)


def _is_rendered(element) -> bool:
    """False when the element or any ancestor is hidden by style or attribute."""
    for node in (element, *element.parents):
        if node.has_attr("hidden") or node.get("aria-hidden") == "true":
            return False
        if _HIDDEN_STYLE.search(node.get("style") or ""):
            return False
    return True


def visible_password_form(soup) -> bool:
    """Whether the document shows a password field to the reader.

    A password field that is not rendered is not a gate: an article page with a
    hidden sign-in modal is still the article, and treating it as a login wall
    throws away the page the caller asked for. Genuine walls render their form.
    """
    return any(
        _is_rendered(field) for field in soup.find_all("input", {"type": "password"})
    )


def classify_page_kind(
    *,
    http_status: int | None,
    has_password_form: bool,
    text_chars: int,
) -> str:
    """Classify a fetched page from structural features alone.

    Order matters: an error status outranks a login form, which outranks the
    length test. Only these two verdicts change what the caller receives -- they
    are the pages a scraper refuses -- so the rest of the vocabulary exists to be
    reported, not to decide.

    Values this function never returns are assigned by callers instead:
    ``download_or_resource`` needs the URL shape, ``listing`` needs a comparison
    across pages of one site. A single page's link density is not evidence for
    either: measured on real pages it reads the same for a reader-shell page made
    of control labels as for an article.
    """
    if http_status is not None and http_status >= CLIENT_ERROR_STATUS:
        return "error_page"
    if has_password_form:
        return "login_or_wall"
    if text_chars >= ARTICLE_MIN_CHARS:
        return "article"
    return "unknown"


@dataclass
class ScrapeOutcome:
    """Everything known about one requested URL.

    ``reason`` answers "why is there no usable content". A run that was degraded
    but still succeeded says so through ``degraded`` / ``resolved_by`` instead,
    so the two causes never share one field and can be counted separately.
    """

    url: str
    fetched: bool = False
    reason: str = "unknown"
    stage: str = "fetch"
    request_id: str = ""
    http_status: int | None = None
    bytes: int = 0
    text_chars: int = 0
    cjk_ratio: float = 0.0
    has_password_form: bool = False
    page_kind: str = "unknown"
    truncated: bool = False
    elapsed_ms: int = 0
    degraded: bool = False
    resolved_by: str = "direct"

    def to_dict(self) -> dict:
        return asdict(self)


def mark_fetch_failure(
    outcome: ScrapeOutcome | None,
    reason: str,
    *,
    stage: str = "fetch",
    http_status: int | None = None,
    fetched: bool | None = None,
    byte_count: int | None = None,
) -> None:
    """Record a transport-level failure. No-op when no outcome was supplied,
    which is what keeps backends that predate outcomes working unchanged.

    ``fetched`` / ``http_status`` / ``byte_count`` are only overwritten when
    given: a quality gate that rejects an already-fetched page must not erase the
    status it was fetched with, and a failure part way through a transfer knows
    things a failure before the request does not.
    """
    if outcome is None:
        return
    outcome.reason = reason
    outcome.stage = stage
    if http_status is not None:
        outcome.http_status = http_status
    if fetched is not None:
        outcome.fetched = fetched
    if byte_count is not None:
        outcome.bytes = byte_count
