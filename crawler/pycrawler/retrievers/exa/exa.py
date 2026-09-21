import os
from ..utils import check_pkg


class ExaSearch:
    """
    Exa API Retriever
    """

    def __init__(self, query, query_domains=None, headers=None):
        """
        Initializes the ExaSearch object.
        Args:
            query: The search query.
        """
        # This validation is necessary since exa_py is optional
        check_pkg("exa_py")
        from exa_py import Exa
        self.query = query
        self.headers = headers or {}
        from pycrawler.retrievers.base import resolve_api_key
        self.api_key = resolve_api_key(headers, "exa_api_key", "EXA_API_KEY")
        self.client = Exa(api_key=self.api_key)
        self.query_domains = query_domains or None

    def _retrieve_api_key(self):
        from pycrawler.retrievers.base import resolve_api_key
        return resolve_api_key(self.headers, "exa_api_key", "EXA_API_KEY")

    def search(
        self, max_results=10, use_autoprompt=False, search_type="neural", **filters
    ):
        """
        Searches the query using the Exa API.
        Args:
            max_results: The maximum number of results to return.
            use_autoprompt: Whether to use autoprompting.
            search_type: The type of search (e.g., "neural", "keyword").
            **filters: Additional filters (e.g., date range, domains).
        Returns:
            A list of search results.
        """
        try:
            results = self.client.search(
                self.query,
                type=search_type,
                use_autoprompt=use_autoprompt,
                num_results=max_results,
                include_domains=self.query_domains,
                **filters
            )
        except Exception as e:
            print(f"Error: {e}. Failed fetching sources from Exa. Empty response.")
            return []

        search_response = []
        rows = getattr(results, "results", None) or []
        if not isinstance(rows, list):
            return []
        for result in rows:
            href = getattr(result, "url", None)
            if not href:
                continue
            body = getattr(result, "text", None) or getattr(result, "summary", None) or ""
            search_response.append({"href": href, "body": body})
        return search_response

    def find_similar(self, url, exclude_source_domain=False, **filters):
        """
        Finds similar documents to the provided URL using the Exa API.
        Args:
            url: The URL to find similar documents for.
            exclude_source_domain: Whether to exclude the source domain in the results.
            **filters: Additional filters.
        Returns:
            A list of similar documents.
        """
        results = self.client.find_similar(
            url, exclude_source_domain=exclude_source_domain, **filters
        )

        similar_response = []
        for result in results.results or []:
            href = getattr(result, "url", None)
            if not href:
                continue
            body = getattr(result, "text", None) or getattr(result, "summary", None) or ""
            similar_response.append({"href": href, "body": body})
        return similar_response

    def get_contents(self, ids, **options):
        """
        Retrieves the contents of the specified IDs using the Exa API.
        Args:
            ids: The IDs of the documents to retrieve.
            **options: Additional options for content retrieval.
        Returns:
            A list of document contents.
        """
        results = self.client.get_contents(ids, **options)

        contents_response = []
        for result in results.results or []:
            result_id = getattr(result, "id", None)
            if result_id is None:
                continue
            content = getattr(result, "text", None) or ""
            contents_response.append({"id": result_id, "content": content})
        return contents_response
