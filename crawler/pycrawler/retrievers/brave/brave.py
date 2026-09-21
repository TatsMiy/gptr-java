# Brave Search Retriever

# libraries
import logging
import os

import requests


class BraveSearch:
    """
    Brave Search API Retriever
    """

    def __init__(self, query, query_domains=None, headers=None):
        """
        Initializes the BraveSearch object
        Args:
            query:
        """
        self.query = query
        self.query_domains = query_domains or None
        self.headers = headers or {}
        from pycrawler.retrievers.base import resolve_api_key
        self.api_key = resolve_api_key(headers, "brave_api_key", "BRAVE_API_KEY")
        self.logger = logging.getLogger(__name__)

    def get_api_key(self):
        from pycrawler.retrievers.base import resolve_api_key
        return resolve_api_key(self.headers, "brave_api_key", "BRAVE_API_KEY")

    def search(self, max_results=7) -> list[dict[str, str]]:
        """
        Searches the query
        Returns:

        """
        print("Searching with query {0}...".format(self.query))
        """Useful for general internet search queries using the Brave Search API."""

        url = "https://api.search.brave.com/res/v1/web/search"
        headers = {
            "X-Subscription-Token": self.api_key,
            "Accept": "application/json",
            "Accept-Encoding": "gzip",
        }
        # TODO: Add support for query domains
        params = {
            "q": self.query,
            "count": min(max_results, 20),
        }

        try:
            response = requests.get(url, headers=headers, params=params, timeout=20)
            response.raise_for_status()
            search_results = response.json()
            if not isinstance(search_results, dict):
                return []
            web = search_results.get("web") or {}
            if not isinstance(web, dict):
                return []
            results = web.get("results") or []
            if not isinstance(results, list):
                return []
        except Exception as e:
            self.logger.error(
                f"Error fetching Brave search results: {e}. Resulting in empty response."
            )
            return []

        if not isinstance(results, list):
            self.logger.warning(
                f"Unexpected Brave web.results type for query: {self.query}"
            )
            return []

        search_results = []

        # Normalize the results to match the format of the other search APIs
        for result in results:
            if not isinstance(result, dict):
                continue
            url = result.get("url")
            if not url:
                continue
            search_result = {
                "title": result.get("title") or "",
                "href": url,
                "body": result.get("description") or "",
            }
            search_results.append(search_result)

        return search_results
