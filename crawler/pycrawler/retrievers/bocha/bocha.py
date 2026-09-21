# BoCha Search Retriever

# 博查（Bocha）Web Search API 检索器。
#
# 关键改造（原因：原实现的静默失败会短路整条检索链）：
#   1) **异常不再吞掉**：原实现 `except: return []` 把"检索失败"伪装成"无结果"，短路了
#      api.py 的 502 上报与 Java 的 Retry/熔断/降级链（与 DDG 检索器同款缺陷）。
#   2) **QPS 限流**：博查按账户累计充值分级限速，Tier 0（￥0）为 **QPS=1 / QPM=30 / QPD=1000**。
#      深研任务每层会并发发出 5–6 条查询 → 必然超 QPS 被拒。这里做**流水线式最小间隔**
#      （只约束"两次发起"的间隔，不等待上一个响应返回），既守住 QPS 又不把并发压成串行。
#   3) 国内直连、无需代理、无验证码——这是相对 DuckDuckGo 的核心优势。
import json
import logging
import os
import threading
import time

import requests

# 进程级节流器：crawler 是单进程 FastAPI，多查询并发都在同一进程内 → 全局锁有效。
_THROTTLE_LOCK = threading.Lock()
_LAST_START = [0.0]


def _throttle():
    """保证两次请求**发起**之间至少间隔 min_interval 秒（默认 1.05s，对应 QPS≈0.95）。

    流水线式：不等上一个响应完成——6 条查询约 6s 内全部发出，各自并行等待响应。
    """
    try:
        min_interval = float(os.environ.get("BOCHA_MIN_INTERVAL", "1.05"))
    except ValueError:
        min_interval = 1.05
    with _THROTTLE_LOCK:
        wait = _LAST_START[0] + min_interval - time.time()
        if wait > 0:
            time.sleep(wait)
        _LAST_START[0] = time.time()


class BoChaSearch():
    """
    BoCha Search Retriever
    """

    def __init__(self, query, query_domains=None, headers=None):
        """
        Initializes the BoChaSearch object
        Args:
            query:
        """
        self.query = query
        self.query_domains = query_domains or None
        self.headers = headers or {}
        from pycrawler.retrievers.base import resolve_api_key
        self.api_key = resolve_api_key(headers, "bocha_api_key", "BOCHA_API_KEY")
        try:
            self.timeout = max(5, int(os.environ.get("BOCHA_TIMEOUT", "20")))
        except ValueError:
            self.timeout = 20

    def search(self, max_results=7) -> list[dict[str]]:
        """
        Searches the query
        Returns:

        """
        url = 'https://api.bochaai.com/v1/web-search'
        headers = {
            'Authorization': f'Bearer {self.api_key}',
            'Content-Type': 'application/json'
        }
        data = {
            "query": self.query,
            "freshness": "noLimit",  # 搜索的时间范围
            "summary": True,         # 是否返回长文本摘要
            "count": max_results
        }

        _throttle()   # QPS 限流（Tier 0 = 1）
        try:
            response = requests.post(url, headers=headers, json=data, timeout=self.timeout)
            response.raise_for_status()
            json_response = response.json()
        except (requests.RequestException, ValueError) as e:
            # 不吞异常（2026-09-10）：失败必须可见 → api.py 502 → Java 弹性层接管。
            # 旧实现 return [] 会让"被限流/网络故障"看起来像"这个词没结果"。
            raise RuntimeError(
                f"bocha search failed: {type(e).__name__}: {e}") from e

        # The BoCha response shape is data.webPages.value; any of these may be
        # missing on an error/empty payload, so walk it defensively rather than
        # KeyError-ing the whole research run.
        _data = json_response if isinstance(json_response, dict) else {}
        if _data.get("code") not in (None, 200):
            # 博查错误体（如鉴权失败/额度耗尽）也是"失败"，不能当作无结果
            raise RuntimeError(
                "bocha api error: code=%s msg=%s" % (_data.get("code"), _data.get("msg")))
        results = (
            (_data.get("data") or {}).get("webPages") or {}
        ).get("value") or []
        if not isinstance(results, list):
            return []

        search_results = []

        # Normalize the results to match the format of the other search APIs.
        # Skip non-dict rows / empty URLs; default missing fields to "".
        for result in results:
            if not isinstance(result, dict):
                continue
            href = result.get("url") or ""
            if not href:
                continue
            search_results.append(
                {
                    "title": result.get("name") or "",
                    "href": href,
                    "body": result.get("snippet") or "",
                }
            )

        return search_results
