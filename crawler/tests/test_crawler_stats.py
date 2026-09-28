"""进程内计数的单测：分桶恒等、归并语义、总量派生、窗口诚实标注。

运行（crawler 目录下，需能 import requests 等运行依赖）：
    python -m unittest discover -s tests -v

无参、无网络、无人工参数，可全自动执行。
"""
import json
import os
import re
import unittest

from pycrawler.scraper.outcome import PAGE_KINDS, REASONS, ScrapeOutcome
from pycrawler.stats import (
    LATENCY_SAMPLE_CAP,
    OTHER_DOMAIN,
    STATUS_CLASSES,
    TOP_DOMAINS,
    WORKERS,
    CrawlerStats,
)

DOCKERFILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "Dockerfile")


def outcome(url="https://example.com/a", reason="ok", page_kind="article",
            http_status=200, elapsed_ms=100, truncated=False):
    """一条抓取结果行；默认是"正常抓到一篇正文"。"""
    return ScrapeOutcome(url=url, reason=reason, page_kind=page_kind,
                         http_status=http_status, elapsed_ms=elapsed_ms,
                         truncated=truncated)


class TotalsAreDerivedTest(unittest.TestCase):
    """总量必须是明细求和得出的，不能是另一个独立计数器。"""

    def test_scrape_total_equals_the_sum_of_its_own_breakdown(self):
        stats = CrawlerStats()
        stats.record_scrape([outcome(), outcome(reason="empty_content"), outcome(reason="ok")])
        scrape = stats.snapshot()["scrape"]
        self.assertEqual(scrape["total"], 3)
        self.assertEqual(scrape["total"], sum(scrape["outcome"].values()))

    def test_search_total_equals_the_sum_of_its_own_breakdown(self):
        stats = CrawlerStats()
        stats.record_search("bocha", 100, 3)
        stats.record_search("bocha", 100, 0)
        stats.record_search("bocha", 100, 0, "TimeoutError")
        search = stats.snapshot()["search"]
        self.assertEqual(search["total"], 3)
        self.assertEqual(search["total"],
                         sum(sum(v.values()) for v in search["by_retriever"].values()))

    def test_a_reason_outside_the_vocabulary_is_counted_not_dropped(self):
        """词表外的值照实输出：吞掉它会让各键之和与总量对不上。"""
        stats = CrawlerStats()
        stats.record_scrape([outcome(reason="something_new")])
        scrape = stats.snapshot()["scrape"]
        self.assertEqual(scrape["outcome"]["something_new"], 1)
        self.assertEqual(scrape["total"], sum(scrape["outcome"].values()))

    def test_every_vocabulary_label_is_present(self):
        """封闭词表的键始终在（未出现的记 0），面板的列才不会跳来跳去。"""
        scrape = CrawlerStats().snapshot()["scrape"]
        self.assertEqual(sorted(scrape["outcome"]), sorted(REASONS))
        self.assertEqual(sorted(scrape["page_kind"]), sorted(PAGE_KINDS))
        self.assertEqual(sorted(scrape["status_class"]), sorted(STATUS_CLASSES))


class RowAccountingTest(unittest.TestCase):
    """每个请求过的 URL 恰好进一个桶：分桶之和 == 行数。"""

    def test_each_row_lands_in_exactly_one_of_every_dimension(self):
        stats = CrawlerStats()
        rows = [
            outcome(),
            outcome(reason="block_challenge", page_kind="login_or_wall", http_status=200),
            outcome(reason="timeout", page_kind="unknown", http_status=None),
            outcome(reason="http_4xx", page_kind="error_page", http_status=404),
        ]
        stats.record_scrape(rows)
        scrape = stats.snapshot()["scrape"]
        self.assertEqual(sum(scrape["outcome"].values()), len(rows))
        self.assertEqual(sum(scrape["page_kind"].values()), len(rows))
        self.assertEqual(sum(scrape["status_class"].values()), len(rows))

    def test_truncated_is_a_count_not_a_reason(self):
        stats = CrawlerStats()
        stats.record_scrape([outcome(truncated=True), outcome(truncated=False)])
        scrape = stats.snapshot()["scrape"]
        self.assertEqual(scrape["truncated"], 1)
        self.assertEqual(scrape["outcome"]["ok"], 2)


class StatusClassTest(unittest.TestCase):
    """状态码类只由已记录的字段判定，不猜。"""

    def test_recorded_status_wins(self):
        stats = CrawlerStats()
        stats.record_scrape([
            outcome(http_status=200),
            outcome(http_status=404, reason="http_4xx"),
            outcome(http_status=503, reason="http_5xx"),
        ])
        status = stats.snapshot()["scrape"]["status_class"]
        self.assertEqual((status["2xx"], status["4xx"], status["5xx"]), (1, 1, 1))

    def test_transport_reasons_get_their_own_class(self):
        stats = CrawlerStats()
        stats.record_scrape([
            outcome(http_status=None, reason="timeout"),
            outcome(http_status=None, reason="network_error"),
        ])
        status = stats.snapshot()["scrape"]["status_class"]
        self.assertEqual((status["timeout"], status["network"]), (1, 1))

    def test_local_refusal_and_parse_errors_are_none(self):
        """本地拒绝与非传输错误不冒充传输类别。"""
        stats = CrawlerStats()
        stats.record_scrape([
            outcome(http_status=None, reason="no_session"),
            outcome(http_status=None, reason="unsafe_url"),
            outcome(http_status=None, reason="exception"),
        ])
        self.assertEqual(stats.snapshot()["scrape"]["status_class"]["none"], 3)


class DomainPoolingTest(unittest.TestCase):
    """域名是无界标签：能读成域名的才成为键，其余归并。"""

    def test_hosts_that_are_not_domains_pool_into_other(self):
        stats = CrawlerStats()
        stats.record_scrape([
            outcome(url="http://93.184.216.34/a"),
            outcome(url="http://localhost:8000/a"),
            outcome(url="not a url at all"),
            outcome(url="http://example.com/a"),
        ])
        scrape = stats.snapshot()["scrape"]
        # 归并桶不进榜单：它是"没被单独列出的那些"的合计，不是一个忙碌的站点
        self.assertEqual([d["domain"] for d in scrape["top_domains"]], ["example.com"])
        self.assertEqual(scrape["other_domains"]["total"], 3)
        self.assertEqual(scrape["other_domains"]["unreadable"], 3)
        self.assertEqual(scrape["other_domains"]["domains"], 0)

    def test_top_domains_is_capped_and_the_rest_is_counted(self):
        stats = CrawlerStats()
        hosts = [f"site{index:02d}.example" for index in range(TOP_DOMAINS + 2)]
        stats.record_scrape([outcome(url=f"https://{host}/a") for host in hosts])
        scrape = stats.snapshot()["scrape"]
        self.assertEqual(len(scrape["top_domains"]), TOP_DOMAINS)
        self.assertEqual(scrape["other_domains"]["domains"], 2)
        named = sum(row["total"] for row in scrape["top_domains"])
        self.assertEqual(named + scrape["other_domains"]["total"], len(hosts))

    def test_ties_are_broken_by_name(self):
        """同样的计数必须给出同样的榜单，否则面板每次刷新都在跳。"""
        stats = CrawlerStats()
        hosts = [f"site{index:02d}.example" for index in range(TOP_DOMAINS + 2)]
        stats.record_scrape([outcome(url=f"https://{host}/a") for host in hosts])
        listed = [row["domain"] for row in stats.snapshot()["scrape"]["top_domains"]]
        self.assertEqual(listed, sorted(hosts)[:TOP_DOMAINS])

    def test_named_domain_carries_its_reasons(self):
        stats = CrawlerStats()
        stats.record_scrape([
            outcome(url="https://blog.example/a"),
            outcome(url="https://blog.example/b", reason="too_short"),
        ])
        row = stats.snapshot()["scrape"]["top_domains"][0]
        self.assertEqual(row["domain"], "blog.example")
        self.assertEqual(row["total"], 2)
        self.assertEqual(row["by_reason"], {"ok": 1, "too_short": 1})

    def test_non_site_reasons_are_named_for_the_caller(self):
        """哪些原因不该算"这个站的失败率"由这里定，调用方不必抄一份。"""
        scrape = CrawlerStats().snapshot()["scrape"]
        self.assertEqual(scrape["non_site_reasons"], ["no_session", "too_large", "unsafe_url"])


class LatencyTest(unittest.TestCase):
    """耗时：固定桶给面板，有界窗口给分位数，窗口不足时如实标注。"""

    def test_unmeasured_duration_is_not_counted_as_instant(self):
        stats = CrawlerStats()
        stats.record_scrape([outcome(elapsed_ms=0)])
        latency = stats.snapshot()["scrape"]["latency_ms"]
        self.assertEqual(latency["total"], 0)
        self.assertEqual(latency["sampled"], 0)
        self.assertEqual(latency["p50"], 0)
        self.assertEqual(sum(latency["buckets"].values()), 0)

    def test_bucket_edges(self):
        stats = CrawlerStats()
        for elapsed in (1, 499, 500, 1999, 2000, 9999, 10000, 99999):
            stats.record_scrape([outcome(elapsed_ms=elapsed)])
        buckets = stats.snapshot()["scrape"]["latency_ms"]["buckets"]
        self.assertEqual(buckets["<500"], 2)
        self.assertEqual(buckets["500-2000"], 2)
        self.assertEqual(buckets["2-10s"], 2)
        self.assertEqual(buckets[">10s"], 2)

    def test_percentiles_come_from_the_retained_window(self):
        stats = CrawlerStats()
        for elapsed in range(1, LATENCY_SAMPLE_CAP + 11):
            stats.record_scrape([outcome(elapsed_ms=elapsed)])
        latency = stats.snapshot()["scrape"]["latency_ms"]
        # 窗口只留最后 LATENCY_SAMPLE_CAP 个 ⇒ 11..1010
        self.assertEqual(latency["total"], LATENCY_SAMPLE_CAP + 10)
        self.assertEqual(latency["sampled"], LATENCY_SAMPLE_CAP)
        self.assertEqual(latency["p50"], 510)
        self.assertEqual(latency["p95"], 960)
        # 桶覆盖全部调用，不受窗口影响
        self.assertEqual(sum(latency["buckets"].values()), LATENCY_SAMPLE_CAP + 10)


class SearchTest(unittest.TestCase):
    """检索计数：结果类别、每查询条数、检索器归并。"""

    def test_result_classes(self):
        stats = CrawlerStats()
        stats.record_search("bocha", 100, 5)
        stats.record_search("bocha", 100, 0)
        stats.record_search("bocha", 100, 0, "TimeoutError")
        self.assertEqual(stats.snapshot()["search"]["by_retriever"]["bocha"],
                         {"ok": 1, "empty": 1, "error": 1})

    def test_unknown_retriever_is_pooled(self):
        """调用方传什么名字都不该成为新标签。"""
        stats = CrawlerStats()
        stats.record_search("definitely-not-a-retriever", 100, 1)
        by_retriever = stats.snapshot()["search"]["by_retriever"]
        self.assertEqual(list(by_retriever), [OTHER_DOMAIN])
        self.assertNotIn("definitely-not-a-retriever", json.dumps(by_retriever))

    def test_result_count_buckets(self):
        stats = CrawlerStats()
        for count in (0, 1, 2, 3, 5, 6, 50):
            stats.record_search("bocha", 100, count)
        buckets = stats.snapshot()["search"]["results_per_query"]["buckets"]
        self.assertEqual(buckets["0"], 1)
        self.assertEqual(buckets["1-2"], 2)
        self.assertEqual(buckets["3-5"], 2)
        self.assertEqual(buckets["6+"], 2)

    def test_search_latency_is_kept(self):
        stats = CrawlerStats()
        stats.record_search("bocha", 2500, 3)
        latency = stats.snapshot()["search"]["latency_ms"]
        self.assertEqual(latency["p50"], 2500)
        self.assertEqual(latency["buckets"]["2-10s"], 1)


class InflightTest(unittest.TestCase):
    """在途请求数：进去要出来，异常路径也不例外。"""

    def test_inflight_returns_to_zero(self):
        stats = CrawlerStats()
        self.assertEqual(stats.snapshot()["inflight"], 0)
        stats.enter_scrape()
        stats.enter_scrape()
        self.assertEqual(stats.snapshot()["inflight"], 2)
        stats.exit_scrape()
        stats.exit_scrape()
        self.assertEqual(stats.snapshot()["inflight"], 0)


class WindowTest(unittest.TestCase):
    """窗口与版本要如实标注，不能把进程内计数呈现成历史累计。"""

    def test_uptime_is_present_and_not_negative(self):
        self.assertGreaterEqual(CrawlerStats().snapshot()["uptime_s"], 0)

    def test_worker_count_is_reported(self):
        self.assertEqual(CrawlerStats().snapshot()["workers"], WORKERS)

    def test_the_image_starts_exactly_one_worker(self):
        """单进程是这些计数覆盖全量的前提；镜像改了这里就红。"""
        with open(DOCKERFILE, encoding="utf-8") as handle:
            cmd = handle.read()
        found = re.search(r'"--workers"\s*,\s*"(\d+)"', cmd)
        self.assertIsNotNone(found, "Dockerfile CMD 里找不到 --workers")
        self.assertEqual(int(found.group(1)), WORKERS)


class NoContentLeakTest(unittest.TestCase):
    """快照里只有计数、域名与耗时，不含 URL 全文、正文或 query。

    ⚠️ 不能断言"不含 `http`"或"不含 `<`"：原因码带 `http_4xx`、耗时桶标签写作 `"<500"`。
    要挡的是 URL 形态（`://`、路径、查询串）与正文片段，不是这些固定字面量。
    """

    def test_snapshot_carries_no_urls_or_text(self):
        stats = CrawlerStats()
        stats.record_scrape([outcome(url="https://secret.example/private/page?token=abc")])
        stats.record_search("bocha", 100, 3)
        body = json.dumps(stats.snapshot(), ensure_ascii=False)
        self.assertNotIn("://", body)
        self.assertNotIn("/private/page", body)
        self.assertNotIn("token", body)
        self.assertNotIn("abc", body)
        self.assertIn("secret.example", body)


if __name__ == "__main__":
    unittest.main()
