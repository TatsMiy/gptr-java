"""健康探头的单测：判定规则、阈值边界、以及"只连本机"这条边界。

运行（crawler 目录下，需能 import requests 等运行依赖）：
    python -m unittest discover -s tests -v

无参、**不出外网**（只连本机回环上自己起的监听口）、无人工参数。
"""
import socket
import time
import unittest

from pycrawler.health import (
    PROXY_PROBE_TIMEOUT_S,
    STALE_SCRAPE_AGE_S,
    _proxy_target,
    health_report,
)
from pycrawler.scraper.outcome import ScrapeOutcome
from pycrawler.stats import CrawlerStats


class FakeClock:
    """可推进的钟：判定依赖"距今多久"，靠睡眠等一分钟以上是行不通的。"""

    def __init__(self, start=1000.0):
        self.now = start

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


def outcome(reason="ok"):
    return ScrapeOutcome(url="https://example.com/a", reason=reason,
                         page_kind="article", http_status=200, elapsed_ms=100)


def listening_port():
    """一个正在监听的本机端口（用于验证"确实连了这个地址"）。"""
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    return server, server.getsockname()[1]


def closed_port():
    """一个刚被释放的本机端口：大概率无人监听。"""
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    port = server.getsockname()[1]
    server.close()
    return port


class ProxyTargetTest(unittest.TestCase):
    """代理地址解析：带 scheme、不带 scheme、无端口三种写法都要认。

    地址一律用文档化的示例（`proxy.internal` + Squid 惯例端口 3128）：
    测试里写真实转发器地址会把本机拓扑带进公开仓。
    """

    def test_standard_url(self):
        self.assertEqual(_proxy_target({"HTTP_PROXY": "http://proxy.internal:3128"}),
                         ("proxy.internal", 3128))

    def test_url_without_scheme_is_accepted(self):
        self.assertEqual(_proxy_target({"HTTP_PROXY": "proxy.internal:3128"}),
                         ("proxy.internal", 3128))

    def test_missing_port_falls_back(self):
        self.assertEqual(_proxy_target({"HTTP_PROXY": "http://proxy.local"})[1], 80)

    def test_https_proxy_is_used_when_http_is_unset(self):
        self.assertEqual(_proxy_target({"HTTPS_PROXY": "http://127.0.0.1:9999"}),
                         ("127.0.0.1", 9999))

    def test_nothing_configured(self):
        self.assertIsNone(_proxy_target({}))
        self.assertIsNone(_proxy_target({"HTTP_PROXY": "  "}))


class ProxyProbeTest(unittest.TestCase):
    """探头只连配置里那个本机地址，不通即降级。"""

    def test_reachable_proxy_is_ok_and_really_connected(self):
        server, port = listening_port()
        try:
            report = health_report(CrawlerStats(), env={"HTTP_PROXY": f"http://127.0.0.1:{port}"})
            self.assertEqual(report["checks"]["proxy_configured"], True)
            self.assertEqual(report["checks"]["proxy_reachable"], True)
            self.assertEqual(report["status"], "ok")
            server.settimeout(PROXY_PROBE_TIMEOUT_S + 1)
            connection, _ = server.accept()
            connection.close()
        finally:
            server.close()

    def test_unreachable_proxy_is_degraded(self):
        port = closed_port()
        report = health_report(CrawlerStats(), env={"HTTP_PROXY": f"http://127.0.0.1:{port}"})
        self.assertEqual(report["checks"]["proxy_reachable"], False)
        self.assertEqual(report["status"], "degraded")

    def test_no_proxy_configured_is_not_a_degradation(self):
        """没配代理是合法配置（直连），不能因此报降级。"""
        report = health_report(CrawlerStats(), env={})
        self.assertEqual(report["checks"]["proxy_configured"], False)
        self.assertIsNone(report["checks"]["proxy_reachable"])
        self.assertEqual(report["status"], "ok")


class ScrapeFreshnessTest(unittest.TestCase):
    """"太久没抓到东西"这条判定，含阈值边界与"从未成功过"。"""

    def test_no_attempt_yet_is_ok(self):
        report = health_report(CrawlerStats(clock=FakeClock()), env={})
        self.assertIsNone(report["checks"]["last_scrape_age_s"])
        self.assertEqual(report["status"], "ok")

    def test_recent_success_is_ok(self):
        clock = FakeClock()
        stats = CrawlerStats(clock=clock)
        stats.record_scrape([outcome()])
        clock.advance(10)
        report = health_report(stats, env={})
        self.assertEqual(report["checks"]["last_scrape_age_s"], 10)
        self.assertEqual(report["status"], "ok")

    def test_age_at_the_threshold_is_still_ok(self):
        """阈值是"超过才算"（>），恰好等于不降级。"""
        clock = FakeClock()
        stats = CrawlerStats(clock=clock)
        stats.record_scrape([outcome()])
        clock.advance(STALE_SCRAPE_AGE_S)
        self.assertEqual(health_report(stats, env={})["status"], "ok")

    def test_age_past_the_threshold_is_degraded(self):
        clock = FakeClock()
        stats = CrawlerStats(clock=clock)
        stats.record_scrape([outcome()])
        clock.advance(STALE_SCRAPE_AGE_S + 1)
        report = health_report(stats, env={})
        self.assertEqual(report["checks"]["last_scrape_age_s"], STALE_SCRAPE_AGE_S + 1)
        self.assertEqual(report["status"], "degraded")

    def test_attempts_without_a_single_success_are_degraded(self):
        """代理没起就是这个形态：一直有尝试、一次都没成功 ⇒ 必须报降级。"""
        clock = FakeClock()
        stats = CrawlerStats(clock=clock)
        stats.record_scrape([outcome(reason="empty_content"), outcome(reason="network_error")])
        clock.advance(5)
        report = health_report(stats, env={})
        self.assertIsNone(report["checks"]["last_scrape_age_s"])
        self.assertEqual(report["status"], "degraded")

    def test_a_later_success_clears_the_staleness(self):
        clock = FakeClock()
        stats = CrawlerStats(clock=clock)
        stats.record_scrape([outcome(reason="empty_content")])
        clock.advance(STALE_SCRAPE_AGE_S + 1)
        self.assertEqual(health_report(stats, env={})["status"], "degraded")
        stats.record_scrape([outcome()])
        self.assertEqual(health_report(stats, env={})["status"], "ok")


class ReportShapeTest(unittest.TestCase):
    """响应形状：判定与依据同在，调用方可以不同意这个判定。"""

    def test_report_carries_status_and_its_inputs(self):
        report = health_report(CrawlerStats(), env={})
        self.assertIn(report["status"], ("ok", "degraded"))
        self.assertEqual(sorted(report["checks"]),
                         ["last_scrape_age_s", "proxy_configured", "proxy_reachable",
                          "stats_uptime_s"])

    def test_uptime_comes_from_the_counter_window(self):
        clock = FakeClock()
        stats = CrawlerStats(clock=clock)
        clock.advance(42)
        self.assertEqual(health_report(stats, env={})["checks"]["stats_uptime_s"], 42)

    def test_probe_returns_promptly_when_nothing_listens(self):
        """健康检查会被高频调用，探不通也不能拖住它。"""
        port = closed_port()
        started = time.monotonic()
        health_report(CrawlerStats(), env={"HTTP_PROXY": f"http://127.0.0.1:{port}"})
        self.assertLess(time.monotonic() - started, PROXY_PROBE_TIMEOUT_S + 1)


if __name__ == "__main__":
    unittest.main()
