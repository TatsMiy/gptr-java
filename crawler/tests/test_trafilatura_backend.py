"""trafilatura 后端的契约：复用默认后端的抓取与结构特征，只替换取文本那一步。

它是 `/scrape` 的**默认**后端（常量 `api.DEFAULT_SCRAPER_BACKEND`），因此依赖是正式项、
本文件不设跳过守卫——默认后端缺失时这套单测必须**失败**而不是静默跳过。

运行（crawler 目录下）：
    python -m unittest discover -s tests -v
"""
import unittest

from bs4 import BeautifulSoup

from pycrawler import api
from pycrawler.scraper.beautiful_soup.beautiful_soup import BeautifulSoupScraper
from pycrawler.scraper.scraper import SCRAPER_BACKENDS, _load_backend
from pycrawler.scraper.trafilatura.trafilatura_scraper import TrafilaturaScraper
from pycrawler.scraper.utils import get_text_from_soup

ARTICLE = "这是一段足够长的正文内容，用于验证主内容抽取能否识别文章区并丢弃站点框架。" * 12
PAGE = (
    '<html><head><title>标题</title></head><body>'
    '<nav>NAV-MARKER 导航链接列表</nav>'
    '<div class="recommend">RECOMMEND-MARKER 相关推荐</div>'
    '<article><h1>文章标题</h1><p>' + ARTICLE + '</p><p>' + ARTICLE + '</p></article>'
    '<footer>FOOTER-MARKER 版权所有</footer></body></html>'
).encode("utf-8")


class _FakeResponse:
    def __init__(self, content):
        self.content = content


class DefaultBackendTest(unittest.TestCase):

    def test_default_backend_is_registered_and_resolves(self):
        # 锁住选择，同时保证它不是个拼错/未注册的名字（那会让每次抓取都 ImportError）。
        self.assertIn(api.DEFAULT_SCRAPER_BACKEND, SCRAPER_BACKENDS)
        self.assertIs(_load_backend(api.DEFAULT_SCRAPER_BACKEND), TrafilaturaScraper)


class TrafilaturaBackendTest(unittest.TestCase):

    def test_shares_fetch_and_structural_features_with_the_default_backend(self):
        # 结构特征（锚密度 / 可见密码框）与页型输入由父类计算 ⇒
        # 换掉取文本那一步不会让已标定的页型阈值换意思。
        self.assertTrue(issubclass(TrafilaturaScraper, BeautifulSoupScraper))

    def test_extraction_keeps_the_article_and_drops_site_chrome(self):
        # 传入的"文档文本"是父类会返回的那份（含导航与页脚）；本后端应当无视它，
        # 自己从原始 HTML 里取主内容。
        document_text = get_text_from_soup(BeautifulSoup(PAGE, "lxml"))
        text = TrafilaturaScraper("https://example.com/a", None)._text_from(
            _FakeResponse(PAGE), document_text)
        self.assertIn("正文内容", text)
        for marker in ("NAV-MARKER", "RECOMMEND-MARKER", "FOOTER-MARKER"):
            self.assertNotIn(marker, text, f"{marker} 属于站点框架，不应出现在正文里")

    def test_page_without_main_content_yields_empty_text(self):
        # 空正文是"抽不出主内容"的如实交代：调用方会把它记成 empty_content，
        # 而不是把整页塞回去冒充成功。
        text = TrafilaturaScraper("https://example.com/a", None)._text_from(
            _FakeResponse(b"<html><body></body></html>"), "")
        self.assertEqual("", text)


if __name__ == "__main__":
    unittest.main()
