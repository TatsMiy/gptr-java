# SearchApi Retriever

# libraries
import logging
import os
import requests
import urllib.parse


class SearchApiSearch():
    """
    SearchApi Retriever
    """
    def __init__(self, query, query_domains=None, headers=None):
        """
        Initializes the SearchApiSearch object
        Args:
            query:
        """
        self.query = query
        self.headers = headers or {}
        from pycrawler.retrievers.base import resolve_api_key
        self.api_key = resolve_api_key(headers, "searchapi_api_key", "SEARCHAPI_API_KEY")

    def get_api_key(self):
        from pycrawler.retrievers.base import resolve_api_key
        return resolve_api_key(self.headers, "searchapi_api_key", "SEARCHAPI_API_KEY")

    def search(self, max_results=7):
        """
        Searches the query
        Returns:

        """
        print("SearchApiSearch: Searching with query {0}...".format(self.query))
        """Useful for general internet search queries using SearchApi."""


        url = "https://www.searchapi.io/api/v1/search"
        params = {
            "q": self.query,
            "engine": "google",
        }

        headers = {
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {self.api_key}',
            'X-SearchApi-Source': 'gpt-researcher'
        }

        encoded_url = url + "?" + urllib.parse.urlencode(params)
        search_response = []

        try:
            response = requests.get(encoded_url, headers=headers, timeout=20)
            if response.status_code == 200:
                search_results = response.json() or {}
                # ``organic_results`` may be absent (e.g. no matches, an error
                # payload, or a non-google engine response). Default to [] so a
                # missing key does not raise KeyError and silently drop every
                # result via the broad ``except`` below.
                results = search_results.get("organic_results") or []
                results_processed = 0
                for result in results:
                    href = result.get("link") or ""
                    # skip youtube results
                    if "youtube.com" in href:
                        continue
                    if results_processed >= max_results:
                        break
                    search_result = {
                        "title": result.get("title") or "",
                        "href": href,
                        "body": result.get("snippet") or "",
                    }
                    search_response.append(search_result)
                    results_processed += 1
        except Exception as e:
            logging.getLogger(__name__).warning(
                "SearchApiSearch: failed fetching sources (%s). "
                "Returning empty response.",
                e,
            )
            search_response = []

        return search_response
