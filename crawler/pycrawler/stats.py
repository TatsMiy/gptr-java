"""Process-local counters for the crawler service.

The service runs one uvicorn worker, so these counters cover the whole process
and reset when it restarts: ``uptime_s`` is the time window the numbers describe,
never a historical total. History belongs to the per-task snapshot the engine
writes.

Labels are closed vocabularies taken from the scrape outcome layer, or capped
sets (domains: the busiest few plus one bucket for everything else). A string
that arrives with a request never becomes a label -- an unknown retriever and a
host that cannot be read as a domain both fold into ``other``. Standard library
only, and nothing here issues a network request.
"""
from collections import Counter, deque
import ipaddress
import math
import os
import time
from urllib.parse import urlsplit

from pycrawler.retriever_factory import retriever_names
from pycrawler.scraper.outcome import NON_SITE_REASONS, PAGE_KINDS, REASONS

# How many domains are named individually before the rest are pooled.
TOP_DOMAINS = 10

# Durations kept for percentiles. The fixed buckets below answer "is it slower
# than before" without holding anything; this window only exists so the reported
# percentiles are measured rather than interpolated from bucket edges.
LATENCY_SAMPLE_CAP = 1000

# Pooling label for hosts this layer will not key on.
OTHER_DOMAIN = "other"

# Transport outcome of one fetch. A local refusal (nothing was sent) and a
# non-transport error both land in "none" rather than being guessed at.
STATUS_CLASSES = ("2xx", "3xx", "4xx", "5xx", "timeout", "network", "none")

# What came back from one search call.
RESULT_CLASSES = ("ok", "empty", "error")

# Result-count buckets; the panel reads "how many results per query".
RESULT_COUNT_BUCKETS = ("0", "1-2", "3-5", "6+")

# Latency buckets: label -> exclusive upper bound in milliseconds. The last entry
# is open ended and therefore has no bound.
LATENCY_BUCKETS = (("<500", 500), ("500-2000", 2000), ("2-10s", 10000), (">10s", None))

# The image starts uvicorn with a single worker (see Dockerfile CMD), which is
# what makes these counters process-wide. Reported with every snapshot so nobody
# reads them as a fleet total; a test pins it to the Dockerfile.
WORKERS = 1

# Build identity, injected by whoever builds the image. Empty means this process
# cannot say which revision it is, which is reported as-is instead of guessed.
BUILD = os.environ.get("GPTR_BUILD_SHA", "")

_KNOWN_RETRIEVERS = frozenset(retriever_names())


def _percentile(sorted_samples, percent):
    """Nearest-rank percentile over an already sorted list; empty list is zero."""
    if not sorted_samples:
        return 0
    rank = math.ceil(percent / 100 * len(sorted_samples))
    return sorted_samples[min(max(rank - 1, 0), len(sorted_samples) - 1)]


def _latency_bucket(elapsed_ms):
    """Bucket label for one duration; the final bucket absorbs every slow call."""
    for label, upper in LATENCY_BUCKETS[:-1]:
        if elapsed_ms < upper:
            return label
    return LATENCY_BUCKETS[-1][0]


def _result_bucket(count):
    """Bucket label for one query's result count."""
    if count <= 0:
        return RESULT_COUNT_BUCKETS[0]
    if count <= 2:
        return RESULT_COUNT_BUCKETS[1]
    if count <= 5:
        return RESULT_COUNT_BUCKETS[2]
    return RESULT_COUNT_BUCKETS[3]


def _status_class(outcome):
    """Transport class of one scrape row.

    A recorded status code is used as-is. Otherwise only the reasons that are
    transport failures by definition get their own class; everything else --
    local refusals, parse errors -- is "none", because no transport verdict can
    be claimed for it.
    """
    status = outcome.http_status
    if status is not None and 200 <= status < 600:
        return f"{status // 100}xx"
    if outcome.reason == "timeout":
        return "timeout"
    if outcome.reason == "network_error":
        return "network"
    return "none"


def _domain_of(url):
    """Label for counting one URL, or the pooling label when it has no domain.

    Only a host that reads as a domain is used as a label: an IP literal or a
    bare host name would let the caller influence the label set.
    """
    try:
        host = (urlsplit(url).hostname or "").lower().rstrip(".")
    except ValueError:
        return OTHER_DOMAIN
    if not host or "." not in host:
        return OTHER_DOMAIN
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return host
    return OTHER_DOMAIN


def _with_defaults(counter, labels):
    """Fixed-label view of a counter: every known label present, unknown ones kept.

    Keeping unknown labels matters: dropping them would make the buckets stop
    summing to the total, which is the one property a reader checks first.
    """
    view = {label: counter.get(label, 0) for label in labels}
    for label in sorted(set(counter) - set(labels)):
        view[label] = counter[label]
    return view


class _Latency:
    """Bucketed durations plus a bounded window for percentiles."""

    def __init__(self):
        self._buckets = Counter()
        self._samples = deque(maxlen=LATENCY_SAMPLE_CAP)
        self._total = 0

    def add(self, elapsed_ms):
        """Record one duration. A non-positive value is left out: for a scraped
        URL it means the attempt never happened, and for a search call it means
        the call finished inside the clock's resolution -- neither says anything
        about how slow the service is."""
        if elapsed_ms <= 0:
            return
        self._total += 1
        self._buckets[_latency_bucket(elapsed_ms)] += 1
        self._samples.append(elapsed_ms)

    def snapshot(self):
        samples = sorted(self._samples)
        return {
            "p50": _percentile(samples, 50),
            "p95": _percentile(samples, 95),
            "buckets": _with_defaults(self._buckets, [label for label, _ in LATENCY_BUCKETS]),
            # `sampled` below `total` says the percentiles cover the retained
            # window, not every call; the buckets above still cover every call.
            "sampled": len(samples),
            "total": self._total,
        }


class CrawlerStats:
    """Counters for one crawler process, updated from the request handlers.

    Totals are never stored: they are summed from the detail counters when a
    snapshot is taken, so a total can never disagree with its own breakdown.
    """

    def __init__(self, clock=time.monotonic):
        self._clock = clock
        self._started = clock()
        self._last_success = None
        self._inflight = 0
        self._outcome = Counter()
        self._page_kind = Counter()
        self._status_class = Counter()
        self._truncated = 0
        self._scrape_latency = _Latency()
        self._domain_reason = Counter()
        self._search = Counter()
        self._result_bucket = Counter()
        self._search_latency = _Latency()

    def enter_scrape(self):
        """One scrape request started."""
        self._inflight += 1

    def exit_scrape(self):
        """One scrape request finished, whatever its outcome."""
        self._inflight -= 1

    def record_scrape(self, outcomes):
        """Fold the rows of one scrape response into the counters.

        Every requested URL has exactly one row, so the outcome counters add up
        to the number of URLs the service was asked for.
        """
        for outcome in outcomes:
            self._outcome[outcome.reason] += 1
            self._page_kind[outcome.page_kind] += 1
            self._status_class[_status_class(outcome)] += 1
            if outcome.truncated:
                self._truncated += 1
            if outcome.reason == "ok":
                self._last_success = self._clock()
            self._scrape_latency.add(outcome.elapsed_ms)
            self._domain_reason[(_domain_of(outcome.url), outcome.reason)] += 1

    def record_search(self, retriever, elapsed_ms, result_count, exception=None):
        """Fold one search call into the counters.

        ``exception`` is the exception class name when the call failed and None
        when it returned. A retriever this service cannot build is counted under
        the pooling label rather than becoming a label of its own.
        """
        name = retriever if retriever in _KNOWN_RETRIEVERS else OTHER_DOMAIN
        if exception is not None:
            result_class = "error"
        elif result_count <= 0:
            result_class = "empty"
        else:
            result_class = "ok"
        self._search[(name, result_class)] += 1
        self._result_bucket[_result_bucket(result_count)] += 1
        self._search_latency.add(elapsed_ms)

    def uptime_s(self):
        """How long this window has been collecting."""
        return int(self._clock() - self._started)

    def total_scrapes(self):
        """Rows counted so far, summed from the reasons like every other total."""
        return sum(self._outcome.values())

    def last_success_age_s(self):
        """Seconds since the last page was fetched successfully, or None."""
        if self._last_success is None:
            return None
        return int(self._clock() - self._last_success)

    def snapshot(self):
        """Current counts, with every total summed from its own detail."""
        top_domains, other_domains = self._domains()
        return {
            "uptime_s": self.uptime_s(),
            "inflight": self._inflight,
            "workers": WORKERS,
            "build": BUILD,
            "scrape": {
                "total": self.total_scrapes(),
                "outcome": _with_defaults(self._outcome, REASONS),
                "page_kind": _with_defaults(self._page_kind, PAGE_KINDS),
                "status_class": _with_defaults(self._status_class, STATUS_CLASSES),
                "truncated": self._truncated,
                "latency_ms": self._scrape_latency.snapshot(),
                "top_domains": top_domains,
                "other_domains": other_domains,
                # Reasons that describe a local refusal: a per-site failure rate
                # that counted them would blame the site for our own limits.
                "non_site_reasons": sorted(NON_SITE_REASONS),
            },
            "search": {
                "total": sum(self._search.values()),
                "by_retriever": self._by_retriever(),
                "results_per_query": {
                    "buckets": _with_defaults(self._result_bucket, RESULT_COUNT_BUCKETS)
                },
                "latency_ms": self._search_latency.snapshot(),
            },
        }

    def _by_retriever(self):
        """Result classes per retriever, fixed labels, retrievers sorted."""
        view = {}
        for name in sorted({name for name, _ in self._search}):
            counter = Counter({
                result_class: count
                for (key, result_class), count in self._search.items()
                if key == name
            })
            view[name] = _with_defaults(counter, RESULT_CLASSES)
        return view

    def _domains(self):
        """The busiest domains named individually, everything else pooled.

        The pooling label itself is never named individually -- it stands for
        "everything we did not name", so putting it in the named list would read
        as if it were one busy site. Ties are broken by name so the same counts
        always produce the same list.
        """
        totals = Counter()
        for (domain, _reason), count in self._domain_reason.items():
            totals[domain] += count
        ranked = sorted(
            ((domain, total) for domain, total in totals.items() if domain != OTHER_DOMAIN),
            key=lambda item: (-item[1], item[0]),
        )
        named = ranked[:TOP_DOMAINS]
        named_set = {domain for domain, _ in named}
        pooled = Counter()
        for (domain, reason), count in self._domain_reason.items():
            if domain not in named_set:
                pooled[reason] += count
        return (
            [
                {
                    "domain": domain,
                    "total": total,
                    "by_reason": self._reasons_of(domain),
                }
                for domain, total in named
            ],
            {
                "total": sum(pooled.values()),
                "by_reason": dict(sorted(pooled.items())),
                # 被并进来的真域名数，与"根本没有可用主机名的行数"分开报：
                # 前者是榜单截断，后者是标签规则，两者的处置动机不同。
                "domains": len(ranked) - len(named),
                "unreadable": totals.get(OTHER_DOMAIN, 0),
            },
        )

    def _reasons_of(self, domain):
        return {
            reason: count
            for (key, reason), count in sorted(self._domain_reason.items())
            if key == domain
        }
