"""请求 id 必须到达工作线程：抓取后端在工作线程里打日志。

线程起来的上下文是空的，contextvars 不会自动跟过去 —— 而"哪个 URL 失败了、
为什么失败"恰恰是在那里打出来的。本文件锁住这一点。

运行（crawler 目录下）：
    python -m unittest discover -s tests -v
"""
import asyncio
import logging
import unittest

from pycrawler.log_context import RequestIdFilter, NO_REQUEST_ID, get_request_id, set_request_id
from pycrawler.scraper.scraper import _in_context
from pycrawler.utils.workers import WorkerPool


class RequestIdInWorkerThreadTest(unittest.TestCase):

    def test_request_id_survives_the_executor_hop(self):
        set_request_id("task-abc")
        pool = WorkerPool(max_workers=1)

        async def run():
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(pool.executor, _in_context(get_request_id))

        self.assertEqual("task-abc", asyncio.run(run()))

    def test_each_hop_gets_its_own_context(self):
        # 同一个 Context 不能进两次；复用包装函数必须是安全的。
        set_request_id("task-1")
        wrapped = _in_context(get_request_id)
        self.assertEqual("task-1", wrapped())
        self.assertEqual("task-1", wrapped())

    def test_without_the_wrapper_the_id_is_absent(self):
        # 记录这条机制本身：这正是 _in_context 存在的理由。
        set_request_id("task-2")
        pool = WorkerPool(max_workers=1)

        async def run():
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(pool.executor, get_request_id)

        self.assertEqual(NO_REQUEST_ID, asyncio.run(run()))

    def test_the_filter_stamps_the_id_onto_a_record(self):
        set_request_id("task-xyz")
        record = logging.LogRecord("n", logging.INFO, "p", 1, "msg", None, None)
        RequestIdFilter().filter(record)
        self.assertEqual("task-xyz", record.request_id)


if __name__ == "__main__":
    unittest.main()
