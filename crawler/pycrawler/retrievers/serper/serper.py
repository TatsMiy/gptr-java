# Google Serper Retriever

# libraries
import os
import requests
import json


class SerperSearch():
    """
    Google Serper Retriever with support for country, language, and date filtering
    """
    def __init__(self, query, query_domains=None, country=None, language=None, time_range=None, exclude_sites=None, headers=None):
        """
        Initializes the SerperSearch object
        Args:
            query (str): The search query string.
            query_domains (list, optional): List of domains to include in the search. Defaults to None.
            country (str, optional): Country code for search results (e.g., 'us', 'kr', 'jp'). Defaults to None.
            language (str, optional): Language code for search results (e.g., 'en', 'ko', 'ja'). Defaults to None.
            time_range (str, optional): Time range filter (e.g., 'qdr:h', 'qdr:d', 'qdr:w', 'qdr:m', 'qdr:y'). Defaults to None.
            exclude_sites (list, optional): List of sites to exclude from search results. Defaults to None.
        """
        self.query = query
        self.query_domains = query_domains or None
        self.country = country or os.getenv("SERPER_REGION")
        self.language = language or os.getenv("SERPER_LANGUAGE")
        self.time_range = time_range or os.getenv("SERPER_TIME_RANGE")
        self.exclude_sites = exclude_sites or self._get_exclude_sites_from_env()
        self.headers = headers or {}
        from pycrawler.retrievers.base import resolve_api_key
        self.api_key = resolve_api_key(headers, "serper_api_key", "SERPER_API_KEY")

    def _get_exclude_sites_from_env(self):
        """
        Gets the list of sites to exclude from environment variables
        Returns:
            list: List of sites to exclude
        """
        exclude_sites_env = os.getenv("SERPER_EXCLUDE_SITES", "")
        if exclude_sites_env:
            # Split by comma and strip whitespace
            return [site.strip() for site in exclude_sites_env.split(",") if site.strip()]
        return []

    def get_api_key(self):
        from pycrawler.retrievers.base import resolve_api_key
        return resolve_api_key(self.headers, "serper_api_key", "SERPER_API_KEY")

    def search(self, max_results=7):
        """
        Searches the query with optional country, language, and time filtering
        Returns:
            list: List of search results with title, href, and body
        """
        print("Searching with query {0}...".format(self.query))
        """Useful for general internet search queries using the Serper API."""

        # Search the query (see https://serper.dev/playground for the format)
        url = "https://google.serper.dev/search"

        headers = {
            'X-API-KEY': self.api_key,
            'Content-Type': 'application/json'
        }

        # Build search parameters
        query_with_filters = self.query

        # Exclude sites using Google search syntax
        if self.exclude_sites:
            for site in self.exclude_sites:
                query_with_filters += f" -site:{site}"

        # Add domain filtering if specified
        if self.query_domains:
            # Add site:domain1 OR site:domain2 OR ... to the search query
            domain_query = " site:" + " OR site:".join(self.query_domains)
            query_with_filters += domain_query

        search_params = {
            "q": query_with_filters,
            "num": max_results
        }

        # Add optional parameters if they exist
        if self.country:
            search_params["gl"] = self.country  # Geographic location (country)

        if self.language:
            search_params["hl"] = self.language  # Host language

        if self.time_range:
            search_params["tbs"] = self.time_range  # Time-based search

        data = json.dumps(search_params)

        resp = requests.request("POST", url, timeout=10, headers=headers, data=data)

        # **非 200 必须抛错**，不得伪装成"空结果"。
        # 实测：无效 key 时上游返回鉴权错误，此前被下面的 `return []` 吞掉，
        # 表现为 HTTP 200 + 空 results ⇒ Java 侧当成"搜索成功但无结果"，静默产出空报告。
        if resp.status_code != 200:
            raise Exception(f"serper http {resp.status_code}: {resp.text[:200]}")

        # Preprocess the results. Always return a list so callers (which do
        # `len(...)` / iterate over the result) never receive None.
        try:
            search_results = json.loads(resp.text)
        except Exception as e:
            raise Exception(f"serper response not JSON: {e}") from e
        if search_results is None or not isinstance(search_results, dict):
            return []

        results = search_results.get("organic") or []
        if not isinstance(results, list):
            return []
        search_results = []

        # Normalize the results to match the format of the other search APIs
        # Excluded sites should already be filtered out by the query parameters
        for result in results:
            if not isinstance(result, dict):
                continue
            href = result.get("link") or result.get("url") or ""
            if not href:
                continue
            search_results.append(
                {
                    "title": result.get("title") or "",
                    "href": href,
                    "body": result.get("snippet") or result.get("body") or "",
                }
            )

        return search_results
