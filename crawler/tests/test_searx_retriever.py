"""SearXNG 检索器的单测：请求参数、超时、以及"引擎全挂 ≠ 零结果"这条失败语义。

运行（crawler 目录下，需能 import requests）：
    python -m unittest discover -s tests -v

**不出网**：`requests.get` 全部被替身接管，只断言发出去的参数与对响应的判读。
"""
import os
import unittest
from unittest import mock

from pycrawler.retrievers.searx.searx import (
    _MAX_PREFETCHED_LEN,
    SearxSearch,
)

_BASE_ENV = {"SEARX_URL": "http://searx.example:8080"}


class FakeResponse:
    """最小的 requests.Response 替身：只要 json()/raise_for_status()/status_code。"""

    def __init__(self, payload=None, json_error=None, status_error=None):
        self._payload = payload
        self._json_error = json_error
        self._status_error = status_error

    def raise_for_status(self):
        if self._status_error is not None:
            raise self._status_error

    def json(self):
        if self._json_error is not None:
            raise self._json_error
        return self._payload


def build(query="测试查询", env=None, response=None):
    """在受控 env 下构造检索器，并返回 (实例, requests.get 替身)。"""
    full_env = dict(_BASE_ENV)
    full_env.update(env or {})
    patcher = mock.patch.dict(os.environ, full_env, clear=False)
    patcher.start()
    for key in ("SEARX_ENGINES", "SEARX_LANGUAGE", "SEARX_TIMEOUT"):
        if key not in (env or {}):
            os.environ.pop(key, None)
    getter = mock.patch(
        "pycrawler.retrievers.searx.searx.requests.get",
        return_value=response if response is not None else FakeResponse({"results": []}),
    )
    fake_get = getter.start()
    retriever = SearxSearch(query)
    return retriever, fake_get, patcher, getter


class RequestParamsTest(unittest.TestCase):
    """#88：引擎名单与语言必须**可选**地出现在请求参数里。"""

    def test_engines_and_language_are_sent_when_configured(self):
        retriever, fake_get, patcher, getter = build(env={
            "SEARX_ENGINES": "yandex, quark ,sogou",
            "SEARX_LANGUAGE": "zh-CN",
        })
        try:
            retriever.search(3)
        finally:
            patcher.stop()
            getter.stop()
        params = fake_get.call_args.kwargs["params"]
        self.assertEqual(params["q"], "测试查询")
        self.assertEqual(params["format"], "json")
        self.assertEqual(params["engines"], "yandex,quark,sogou")
        self.assertEqual(params["language"], "zh-CN")

    def test_engines_and_language_are_absent_when_unset(self):
        retriever, fake_get, patcher, getter = build()
        try:
            retriever.search(3)
        finally:
            patcher.stop()
            getter.stop()
        params = fake_get.call_args.kwargs["params"]
        self.assertNotIn("engines", params)
        self.assertNotIn("language", params)

    def test_blank_engines_value_is_treated_as_unset(self):
        retriever, fake_get, patcher, getter = build(env={"SEARX_ENGINES": " , ,"})
        try:
            retriever.search(3)
        finally:
            patcher.stop()
            getter.stop()
        self.assertNotIn("engines", fake_get.call_args.kwargs["params"])


class TimeoutTest(unittest.TestCase):
    """无超时会挂死调用方；默认值与非数字回落必须成立。"""

    def _timeout(self, env=None):
        retriever, fake_get, patcher, getter = build(env=env)
        try:
            retriever.search(3)
        finally:
            patcher.stop()
            getter.stop()
        return fake_get.call_args.kwargs["timeout"]

    def test_default_timeout_is_sent(self):
        self.assertEqual(self._timeout(), 30)

    def test_env_timeout_is_used(self):
        self.assertEqual(self._timeout({"SEARX_TIMEOUT": "7"}), 7)

    def test_too_small_timeout_is_clamped(self):
        self.assertEqual(self._timeout({"SEARX_TIMEOUT": "1"}), 5)

    def test_non_numeric_timeout_falls_back(self):
        self.assertEqual(self._timeout({"SEARX_TIMEOUT": "abc"}), 30)


class ResultShapeTest(unittest.TestCase):
    """返回契约不变：url/href 取真值，snippet 截到上限（否则上游当"已抓全文"）。"""

    def test_url_and_href_are_both_accepted_and_body_is_capped(self):
        long_body = "字" * (_MAX_PREFETCHED_LEN + 50)
        payload = {"results": [
            {"url": "https://a.example/1", "content": long_body},
            {"href": "https://b.example/2", "snippet": "短"},
            {"no_url": True},
            "not-a-dict",
        ]}
        retriever, _fake_get, patcher, getter = build(response=FakeResponse(payload))
        try:
            out = retriever.search(5)
        finally:
            patcher.stop()
            getter.stop()
        self.assertEqual([item["href"] for item in out],
                         ["https://a.example/1", "https://b.example/2"])
        self.assertEqual(len(out[0]["body"]), _MAX_PREFETCHED_LEN)
        self.assertEqual(out[1]["body"], "短")

    def test_max_results_is_respected(self):
        payload = {"results": [{"url": "https://x.example/%d" % i} for i in range(10)]}
        retriever, _fake_get, patcher, getter = build(response=FakeResponse(payload))
        try:
            out = retriever.search(2)
        finally:
            patcher.stop()
            getter.stop()
        self.assertEqual(len(out), 2)


class FailureSemanticsTest(unittest.TestCase):
    """#87：引擎全挂必须抛异常（→ api.py 502），真零结果才返回 []。"""

    def test_empty_results_with_unresponsive_engines_raises(self):
        payload = {
            "results": [],
            "unresponsive_engines": [["duckduckgo", "CAPTCHA"],
                                     ["brave", "too many requests"]],
        }
        retriever, _fake_get, patcher, getter = build(response=FakeResponse(payload))
        try:
            with self.assertRaises(RuntimeError) as ctx:
                retriever.search(5)
        finally:
            patcher.stop()
            getter.stop()
        message = str(ctx.exception)
        self.assertIn("duckduckgo", message)
        self.assertIn("CAPTCHA", message)
        self.assertIn("brave", message)

    def test_empty_results_without_unresponsive_engines_returns_empty(self):
        retriever, _fake_get, patcher, getter = build(
            response=FakeResponse({"results": [], "unresponsive_engines": []}))
        try:
            self.assertEqual(retriever.search(5), [])
        finally:
            patcher.stop()
            getter.stop()

    def test_results_with_unresponsive_engines_still_return(self):
        payload = {
            "results": [{"url": "https://ok.example/1", "content": "x"}],
            "unresponsive_engines": [["brave", "too many requests"]],
        }
        retriever, _fake_get, patcher, getter = build(response=FakeResponse(payload))
        try:
            with self.assertLogs("pycrawler.retrievers.searx.searx", level="WARNING"):
                out = retriever.search(5)
        finally:
            patcher.stop()
            getter.stop()
        self.assertEqual(len(out), 1)

    def test_non_json_response_raises(self):
        retriever, _fake_get, patcher, getter = build(
            response=FakeResponse(json_error=ValueError("no json")))
        try:
            with self.assertRaises(RuntimeError):
                retriever.search(5)
        finally:
            patcher.stop()
            getter.stop()

    def test_json_that_is_not_an_object_raises(self):
        retriever, _fake_get, patcher, getter = build(response=FakeResponse([1, 2, 3]))
        try:
            with self.assertRaises(RuntimeError):
                retriever.search(5)
        finally:
            patcher.stop()
            getter.stop()

    def test_results_that_is_not_a_list_raises(self):
        retriever, _fake_get, patcher, getter = build(
            response=FakeResponse({"results": "oops"}))
        try:
            with self.assertRaises(RuntimeError):
                retriever.search(5)
        finally:
            patcher.stop()
            getter.stop()

    def test_http_error_raises(self):
        import requests
        retriever, _fake_get, patcher, getter = build(
            response=FakeResponse(status_error=requests.HTTPError("500 Server Error")))
        try:
            with self.assertRaises(RuntimeError) as ctx:
                retriever.search(5)
        finally:
            patcher.stop()
            getter.stop()
        self.assertIn("500", str(ctx.exception))

    def test_missing_instance_url_raises(self):
        # 键**缺失**才触发（键存在但为空串时 get_searxng_url 不报错，属既存语义，本测试不覆盖）
        patcher = mock.patch.dict(os.environ, {}, clear=False)
        patcher.start()
        saved = os.environ.pop("SEARX_URL", None)
        try:
            with self.assertRaises(Exception) as ctx:
                SearxSearch("q")
        finally:
            patcher.stop()
            if saved is not None:
                os.environ["SEARX_URL"] = saved
        self.assertIn("SEARX_URL", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
