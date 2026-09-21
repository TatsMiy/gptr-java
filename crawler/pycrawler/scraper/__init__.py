# 惰性后端注册（自 gpt-researcher 提取，Apache-2.0）：
# 各后端在 scraper/scraper.py 的 get_scraper() 中按需 import（避免顶层导入
# 强依赖 selenium/zendriver/firecrawl/langchain-community 等重型可选依赖）。
from .scraper import Scraper

__all__ = [
    "Scraper",
]
