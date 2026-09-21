# 检索器包（自 gpt-researcher 提取，Apache-2.0）。
# 注意：本包为惰性设计——不在 __init__ 顶层导入任何引擎（每个引擎有独立第三方
# 依赖：tavily/exa/ddgs/arxiv…），一律经 retriever_factory.get_retriever(name)
# 按需导入，避免整包导入拉全部重型可选依赖。
