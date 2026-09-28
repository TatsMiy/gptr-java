"""Dependency probe behind ``GET /health``.

The endpoint used to answer a constant ``ok``, which cannot tell "the service is
up" from "the service is up but its network path is gone" -- and the second case
is the one that quietly returns empty results. So it reports what it can check
locally: whether a network path is configured, whether that path accepts a
connection, and how long ago a page was last fetched.

Nothing here talks to the outside world. The connection check targets the
configured forwarder's own address and port, never a remote site: a health check
that pulls real pages would turn monitoring into load and traffic.
"""
import os
import socket
from urllib.parse import urlsplit

# No successful fetch for this long means the crawl path is not working. It sits
# well above any backend's own timeout, so ordinary slow pages cannot trip it.
STALE_SCRAPE_AGE_S = 600

# The probe must not make a health check slow. A refused connection returns at
# once; this only bounds an attempt that is silently dropped.
PROXY_PROBE_TIMEOUT_S = 1.0

# Port assumed when the setting names none: an http proxy URL without a port
# means port 80.
PROXY_DEFAULT_PORT = 80

_PROXY_ENV_KEYS = ("HTTP_PROXY", "HTTPS_PROXY")


def _proxy_target(env):
    """``(host, port)`` of the configured network path, or None when none is set.

    A setting without a scheme is accepted, because that is how these variables
    are often written by hand.
    """
    for key in _PROXY_ENV_KEYS:
        url = (env.get(key) or "").strip()
        if not url:
            continue
        parsed = urlsplit(url if "//" in url else "//" + url)
        if parsed.hostname:
            return parsed.hostname, parsed.port or PROXY_DEFAULT_PORT
    return None


def _accepts_connection(target):
    """Whether anything answers at that address."""
    try:
        with socket.create_connection(target, timeout=PROXY_PROBE_TIMEOUT_S):
            return True
    except OSError:
        return False


def health_report(stats, env=None):
    """Verdict plus the facts it was reached from.

    The status is a judgement, so its inputs travel with it: a caller that
    disagrees with the rule can apply its own without another endpoint. A
    degraded service still answers, so the response is not an error status --
    "not answering" and "answering that something is wrong" must stay distinct.
    """
    environment = os.environ if env is None else env
    target = _proxy_target(environment)
    reachable = _accepts_connection(target) if target else None
    age_s = stats.last_success_age_s()
    checks = {
        "proxy_configured": target is not None,
        "proxy_reachable": reachable,
        "last_scrape_age_s": age_s,
        "stats_uptime_s": stats.uptime_s(),
    }
    if target and not reachable:
        return {"status": "degraded", "checks": checks}
    # Attempts have been made and none ever succeeded, or the last success is
    # too old. "Never succeeded" counts as broken: a forwarder that was never
    # started produces exactly that shape.
    if stats.total_scrapes() > 0 and (age_s is None or age_s > STALE_SCRAPE_AGE_S):
        return {"status": "degraded", "checks": checks}
    return {"status": "ok", "checks": checks}
