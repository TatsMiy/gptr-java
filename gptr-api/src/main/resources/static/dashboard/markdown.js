/* 报告渲染的唯一入口：markdown → 安全 HTML。
 *
 * 管线固定为「先解析、后净化」：`marked` 把 markdown 变成 HTML，`DOMPurify` 再按白名单清洗。
 * **顺序不能颠倒** —— 净化器必须看到解析结果，否则报告里的原始 HTML 会绕过白名单。
 *
 * 为什么单独一个文件（而不是写进 dashboard.js）：它要能**被独立调用**（`GPTR_MD.render`）
 * 才谈得上被检查；塞进整页脚本里就只能靠"打开浏览器看一眼"。
 * 本文件不读 DOM、不发请求、不依赖 i18n；除两个库的全局外没有任何依赖。
 */
"use strict";

(function () {
  /* 允许的标签：够渲染我们自己的报告即可，不是"尽量多"。
   * 少一个白名单项 = 少一类要担心的输入。 */
  const ALLOWED_TAGS = [
    "h1", "h2", "h3", "h4", "h5", "h6", "p", "br", "hr",
    "strong", "em", "code", "pre", "blockquote",
    "ul", "ol", "li",
    "table", "thead", "tbody", "tr", "th", "td",
    "a", "span",
  ];

  /* ⚠️ `id` 必须在这张表里：报告的参考文献写成 `<a id="ref-N"></a>`，正文里是 `[n](#ref-N)`。
   * 净化掉 `id` 的后果是"引用点了不跳"，**而且不报错** —— 这是最容易漏的一条。 */
  const ALLOWED_ATTR = ["href", "id", "class", "colspan", "rowspan", "align", "start"];

  /* 只放行 http(s)、页内锚点与 mailto：`javascript:` / `data:` / `file:` 一律剥掉 href。
   * 这条与白名单标签是两道独立的闸 —— 标签合法不等于链接目标合法。 */
  const ALLOWED_URI = /^(?:https?:|#|mailto:)/i;

  /**
   * 渲染一份报告。
   *
   * @param {string} md 报告原文（markdown）
   * @returns {string|null} 安全的 HTML；**返回 null 表示"这次渲染不可用"**，
   *   调用方据此回退到原文显示（库没加载 / 解析异常 / 净化后为空都走这条）。
   *   空输入返回空串（那是"报告为空"，与"渲染失败"不是一回事）。
   */
  function render(md) {
    if (typeof md !== "string" || !md.trim()) {
      return "";
    }
    if (!window.marked || !window.DOMPurify) {
      return null;
    }
    try {
      // 幂等，且库晚于本文件加载也不会漏设
      window.marked.setOptions({ gfm: true, breaks: false });
      const clean = window.DOMPurify.sanitize(window.marked.parse(md), {
        USE_PROFILES: { html: true },        // 关掉 SVG / MathML
        ALLOWED_TAGS: ALLOWED_TAGS,
        ALLOWED_ATTR: ALLOWED_ATTR,
        ALLOW_DATA_ATTR: false,
        ALLOWED_URI_REGEXP: ALLOWED_URI,
      });
      // 净化后为空 ⇒ 当作"渲染不可用"：显示白板比显示原文更糟
      return clean && clean.trim() ? clean : null;
    } catch (e) {
      console.warn("markdown render failed, falling back to raw text: " + e);
      return null;
    }
  }

  window.GPTR_MD = { render };
})();
