import logging
import time

import requests
from bs4 import BeautifulSoup

from ..outcome import cjk_count, http_status_reason, mark_fetch_failure, visible_password_form
from ..utils import get_relevant_images, extract_title, get_text_from_soup, clean_soup

logger = logging.getLogger(__name__)

# Response codes worth one retry: rate limiting and transient server errors.
RETRYABLE_STATUS_CODES = {429, 500, 502, 503, 504}
MAX_CONTENT_BYTES = 10 * 1024 * 1024  # Skip pages larger than 10MB


class BeautifulSoupScraper:

    def __init__(self, link, session=None):
        self.link = link
        self.session = session

    def scrape(self, outcome=None):
        """Fetch the page and extract cleaned text, images and title.

        Args:
            outcome: Optional ScrapeOutcome to fill with the transport facts and
                the structural features measured here. Without one the method
                behaves exactly as before.

        Returns:
            Tuple of (content, image_urls, title). Empty values are returned
            when the page cannot be fetched or yields no usable content.
        """
        response = self._fetch(outcome)
        if response is None:
            return "", [], ""

        try:
            # response.encoding defaults to ISO-8859-1 when the Content-Type
            # header omits a charset, which garbles many UTF-8 pages. Only
            # trust it when the server actually declared a charset; otherwise
            # let BeautifulSoup detect the encoding from the document itself.
            content_type = response.headers.get("Content-Type", "")
            declared_encoding = response.encoding if "charset" in content_type.lower() else None
            soup = BeautifulSoup(
                response.content, "lxml", from_encoding=declared_encoding
            )

            soup = clean_soup(soup)

            # Features have to be measured while the soup is still alive: what
            # reaches the caller is cleaned plain text, with no form left to
            # inspect.
            #
            # They describe the *document*, not whichever text extractor produced
            # the returned content. A ratio whose numerator came from the whole
            # document and whose denominator came from a shorter extracted text
            # would be meaningless, so both sides come from this text.
            document_text = get_text_from_soup(soup)
            document_chars = len(document_text)
            if outcome is not None:
                outcome.cjk_ratio = cjk_count(document_text) / max(1, document_chars)
                outcome.has_password_form = visible_password_form(soup)

            content = self._text_from(response, document_text)

            if outcome is not None:
                outcome.text_chars = len(content)

            image_urls = get_relevant_images(soup, self.link)

            # Extract the title using the utility function
            title = extract_title(soup)

            return content, image_urls, title

        except Exception as e:
            logger.error(f"Error parsing {self.link}: {e}")
            mark_fetch_failure(outcome, "exception", stage="parse")
            return "", [], ""

    def _text_from(self, response, document_text) -> str:
        """Text handed to the caller, given the cleaned document text.

        Kept as its own step so a backend that extracts main content differently
        can reuse this class's fetching, its structural features and its page-kind
        inputs unchanged, and replace only how the text is obtained.
        """
        return document_text

    def _fetch(self, outcome=None):
        """GET the page, retrying once on transient failures.

        Returns the response on success, or None when the page is
        unreachable, an error status, or too large to be worth parsing. The
        reason is written to ``outcome`` when one is supplied.
        """
        if self.session is None:
            logger.warning(f"No session provided for {self.link}; cannot fetch")
            mark_fetch_failure(outcome, "no_session")
            return None

        for attempt in (1, 2):
            try:
                response = self.session.get(self.link, timeout=10)
            except requests.exceptions.Timeout as e:
                logger.warning(f"Request timed out for {self.link} (attempt {attempt}): {e}")
                if attempt == 1:
                    time.sleep(1)
                    continue
                mark_fetch_failure(outcome, "timeout")
                return None
            except Exception as e:
                logger.warning(f"Request failed for {self.link} (attempt {attempt}): {e}")
                if attempt == 1:
                    time.sleep(1)
                    continue
                mark_fetch_failure(outcome, "network_error")
                return None

            if response.status_code in RETRYABLE_STATUS_CODES and attempt == 1:
                logger.warning(
                    f"Got HTTP {response.status_code} for {self.link}, retrying once"
                )
                time.sleep(1)
                continue

            if response.status_code >= 400:
                # Don't parse error/paywall pages as if they were content
                logger.warning(f"Got HTTP {response.status_code} for {self.link}, skipping")
                mark_fetch_failure(
                    outcome,
                    http_status_reason(response.status_code),
                    http_status=response.status_code,
                    fetched=True,
                )
                return None

            content_length = response.headers.get("Content-Length")
            if content_length:
                try:
                    length_val = int(str(content_length).strip())
                except (TypeError, ValueError):
                    length_val = None
                if length_val is not None and length_val > MAX_CONTENT_BYTES:
                    logger.warning(
                        f"Content too large for {self.link} ({content_length} bytes), skipping"
                    )
                    mark_fetch_failure(
                        outcome,
                        "too_large",
                        http_status=response.status_code,
                        fetched=True,
                        byte_count=length_val,
                    )
                    return None

            if outcome is not None:
                outcome.fetched = True
                outcome.http_status = response.status_code
                outcome.bytes = len(response.content)
            return response

        return None
