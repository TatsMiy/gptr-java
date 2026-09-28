"""抓取编排层的拒绝路径单测：拒绝原因必须分类正确。

运行（crawler 目录下）：
    python -m unittest discover -s tests -v

无参、无人工参数；只做主机名解析与本地策略判定，不发任何 HTTP 请求。
"""
import asyncio
import unittest

from pycrawler.scraper.scraper import Scraper
from pycrawler.utils.workers import WorkerPool

# RFC 2606 保留域名：解析必然失败，且不需要外网。
UNRESOLVABLE_URL = "http://gptr-unresolvable-host.invalid/page"
# 私网地址：本地策略必须拒绝（SSRF 防护）。
PRIVATE_TARGET_URL = "http://127.0.0.1:9/page"


class UrlRejectionReasonTest(unittest.TestCase):

    def extract(self, url):
        pool = WorkerPool(max_workers=1)
        scraper = Scraper(urls=[url], user_agent="test-agent", scraper="bs",
                          worker_pool=pool, request_id="t-1")
        return asyncio.run(scraper.extract_data_from_url(url, None))

    def test_unresolvable_host_is_a_network_error_not_a_policy_refusal(self):
        # 同一个异常类型覆盖两类成因，这里锁住"解析失败算传输层问题"这一判定：
        # 把它记成本地策略拒绝会让"哪个站在拦我们"的统计失真。
        row = self.extract(UNRESOLVABLE_URL)
        self.assertEqual("network_error", row["outcome"].reason)
        self.assertFalse(row["outcome"].fetched)

    def test_private_target_is_a_policy_refusal(self):
        row = self.extract(PRIVATE_TARGET_URL)
        self.assertEqual("unsafe_url", row["outcome"].reason)

    def test_a_rejected_url_still_produces_a_row_with_its_request_id(self):
        row = self.extract(UNRESOLVABLE_URL)
        self.assertEqual(UNRESOLVABLE_URL, row["url"])
        self.assertEqual("t-1", row["outcome"].request_id)
        self.assertIsNone(row["raw_content"])

    def test_run_keeps_rows_that_produced_no_content(self):
        pool = WorkerPool(max_workers=1)
        scraper = Scraper(urls=[UNRESOLVABLE_URL], user_agent="test-agent", scraper="bs",
                          worker_pool=pool, request_id="t-1")
        rows = asyncio.run(scraper.run())
        self.assertEqual(1, len(rows), "没有正文的行也必须留在结果里，否则原因又丢了")
        self.assertIsNone(rows[0]["raw_content"])
        self.assertEqual("network_error", rows[0]["outcome"].reason)


if __name__ == "__main__":
    unittest.main()
