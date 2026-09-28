"""抓取结果归因的单测：取值域封闭 + 页型判定规则。

运行（crawler 目录下，需能 import requests 等运行依赖）：
    python -m unittest discover -s tests -v

无参、无网络、无人工参数，可全自动执行。
"""
import unittest

from pycrawler.scraper.outcome import (
    ARTICLE_MIN_CHARS,
    NON_SITE_REASONS,
    PAGE_KINDS,
    REASONS,
    STAGES,
    ScrapeOutcome,
    classify_page_kind,
    http_status_reason,
    mark_fetch_failure,
)


class VocabularyTest(unittest.TestCase):
    """判据 7：reason / page_kind 取值域封闭。"""

    def test_fresh_outcome_uses_known_codes_only(self):
        outcome = ScrapeOutcome(url="https://example.com")
        self.assertIn(outcome.reason, REASONS)
        self.assertIn(outcome.page_kind, PAGE_KINDS)
        self.assertIn(outcome.stage, STAGES)

    def test_unknown_belongs_to_the_vocabulary(self):
        # 后端不填 outcome 时留下的就是 unknown。它若不在取值域里，
        # "所有 reason 都合法"这条断言反而会在最常见的缺口上失效。
        self.assertIn("unknown", REASONS)
        self.assertIn("unknown", PAGE_KINDS)

    def test_non_site_reasons_are_actual_reasons(self):
        self.assertTrue(NON_SITE_REASONS.issubset(set(REASONS)))

    def test_vocabularies_have_no_duplicates(self):
        for vocabulary in (REASONS, PAGE_KINDS, STAGES):
            self.assertEqual(len(vocabulary), len(set(vocabulary)))

    def test_http_status_maps_into_two_classes(self):
        for status in (400, 403, 404, 429):
            self.assertEqual("http_4xx", http_status_reason(status))
        for status in (500, 502, 503, 504):
            self.assertEqual("http_5xx", http_status_reason(status))


class PageKindTest(unittest.TestCase):
    """页型判定：只看结构特征，规则顺序固定。"""

    def kind(self, **overrides):
        facts = {
            "http_status": 200,
            "has_password_form": False,
            "text_chars": 4000,
        }
        facts.update(overrides)
        return classify_page_kind(**facts)

    def test_error_status_outranks_everything_else(self):
        self.assertEqual("error_page", self.kind(http_status=403, has_password_form=True))

    def test_password_form_means_login_wall(self):
        self.assertEqual("login_or_wall", self.kind(has_password_form=True))

    def test_long_enough_page_is_an_article(self):
        self.assertEqual("article", self.kind())

    def test_page_below_the_length_floor_is_unknown(self):
        # 低于 ARTICLE_MIN_CHARS 说明"这里没有一篇文档"，但还不至于像登录墙/错误页
        # 那样被拒收，故记 unknown 而不是 article。
        self.assertEqual("unknown", self.kind(text_chars=200))

    def test_the_length_floor_itself_is_an_article(self):
        self.assertEqual("article", self.kind(text_chars=ARTICLE_MIN_CHARS))

    def test_values_needing_more_than_one_page_are_never_produced(self):
        # 这两个取值分别需要 URL 形态与"同站跨页比较"，单页视角给不出，
        # 故只能由调用方赋予 —— 本函数在任何输入下都不应返回它们。
        for chars in (0, 100, 500, 3000, 50000):
            for form in (False, True):
                kind = self.kind(text_chars=chars, has_password_form=form)
                self.assertNotIn(kind, ("download_or_resource", "listing"))


class OutcomeShapeTest(unittest.TestCase):
    """字段契约：失败原因与降级原因是两个字段，二者不互相覆盖。"""

    def test_to_dict_carries_every_field_callers_read(self):
        payload = ScrapeOutcome(url="https://example.com").to_dict()
        for field in (
            "url",
            "fetched",
            "reason",
            "stage",
            "request_id",
            "http_status",
            "bytes",
            "text_chars",
            "cjk_ratio",
            "has_password_form",
            "page_kind",
            "truncated",
            "elapsed_ms",
            "degraded",
            "resolved_by",
        ):
            self.assertIn(field, payload)

    def test_a_successful_outcome_can_also_be_marked_degraded(self):
        # 字段契约：两个事实并列存在、各自可读。
        # 谁写下这两个值由 PDF 兜底重试路径负责（需真后端，不在单测范围内）。
        outcome = ScrapeOutcome(url="https://example.com")
        outcome.reason = "ok"
        outcome.degraded = True
        outcome.resolved_by = "pdf_retry"
        payload = outcome.to_dict()
        self.assertEqual("ok", payload["reason"])
        self.assertTrue(payload["degraded"])
        self.assertEqual("pdf_retry", payload["resolved_by"])

    def test_a_failure_keeps_the_degradation_flag_separate(self):
        outcome = ScrapeOutcome(url="https://example.com")
        mark_fetch_failure(outcome, "timeout")
        payload = outcome.to_dict()
        self.assertEqual("timeout", payload["reason"])
        self.assertFalse(payload["degraded"])

    def test_failure_marking_does_not_erase_known_facts(self):
        # 质量闸拒绝一个"已经抓到、HTTP 200、有字节数"的页面时，
        # 这些事实必须留下——否则登录墙页会显示成"根本没连上"。
        outcome = ScrapeOutcome(url="https://example.com", fetched=True, http_status=200, bytes=1234)
        mark_fetch_failure(outcome, "block_challenge", stage="quality")
        self.assertTrue(outcome.fetched)
        self.assertEqual(200, outcome.http_status)
        self.assertEqual(1234, outcome.bytes)
        self.assertEqual("quality", outcome.stage)

    def test_failure_marking_overwrites_when_facts_are_given(self):
        outcome = ScrapeOutcome(url="https://example.com")
        mark_fetch_failure(outcome, "http_5xx", http_status=503, fetched=True, byte_count=77)
        self.assertEqual(503, outcome.http_status)
        self.assertTrue(outcome.fetched)
        self.assertEqual(77, outcome.bytes)

    def test_failure_marking_without_an_outcome_is_a_no_op(self):
        mark_fetch_failure(None, "timeout")


if __name__ == "__main__":
    unittest.main()
