"""可见密码框判定：隐藏的登录弹窗不算登录墙。

夹具取自实测页面的结构（donews 的隐藏 modal / shlab 的 div 包裹 / medlive 的 form），
无网络、无人工参数。

运行（crawler 目录下）：
    python -m unittest discover -s tests -v
"""
import unittest

from bs4 import BeautifulSoup

from pycrawler.scraper.outcome import visible_password_form

# 实测形态：文章页给每个访客都预置了一个隐藏的登录弹窗。
HIDDEN_MODAL = """
<body>
  <div class="modal-bg" style="display:none">
    <div class="modal-container">
      <div class="modal-input-container">
        <input class="modal-input" type="password">
      </div>
    </div>
  </div>
  <article><p>正文</p></article>
</body>
"""

# 实测形态：真登录闸（表单内）。
RENDERED_IN_FORM = """
<body>
  <form class="fm-v clearfix">
    <div class="loginInput-li"><input class="login-input-ones js-input" type="password"></div>
  </form>
</body>
"""

# 实测形态：真登录闸（普通 div 包裹，不在 form 内）。
RENDERED_IN_DIV = """
<body>
  <div class="m-con"><div class="m-area"><div class="mima">
    <input class="box-siz" type="password">
  </div></div></div>
</body>
"""


class VisiblePasswordFormTest(unittest.TestCase):

    def check(self, html):
        return visible_password_form(BeautifulSoup(html, "lxml"))

    def test_hidden_modal_is_not_a_gate(self):
        self.assertFalse(self.check(HIDDEN_MODAL))

    def test_rendered_field_inside_a_form_is_a_gate(self):
        self.assertTrue(self.check(RENDERED_IN_FORM))

    def test_rendered_field_inside_divs_is_a_gate(self):
        self.assertTrue(self.check(RENDERED_IN_DIV))

    def test_visibility_hidden_ancestor_hides_the_gate(self):
        self.assertFalse(self.check('<body><div style="visibility: hidden">'
                                    '<input type="password"></div></body>'))

    def test_hidden_attribute_ancestor_hides_the_gate(self):
        self.assertFalse(self.check('<body><div hidden>'
                                    '<input type="password"></div></body>'))

    def test_aria_hidden_ancestor_hides_the_gate(self):
        self.assertFalse(self.check('<body><div aria-hidden="true">'
                                    '<input type="password"></div></body>'))

    def test_a_hidden_field_is_not_a_gate(self):
        self.assertFalse(self.check('<body><input type="password" style="display:none"></body>'))

    def test_page_without_a_password_field(self):
        self.assertFalse(self.check("<body><article>正文</article></body>"))

    def test_one_rendered_gate_outweighs_a_hidden_one(self):
        html = """
        <body>
          <div style="display:none"><input type="password"></div>
          <form><input type="password"></form>
        </body>
        """
        self.assertTrue(self.check(html))


if __name__ == "__main__":
    unittest.main()
