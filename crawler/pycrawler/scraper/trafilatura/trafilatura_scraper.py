"""Main-text extraction with trafilatura, on top of the default fetcher.

Only the text step differs from the default backend: fetching, the transport
facts and the structural features (anchor density, visible password field) all
come from the same code, so the page-kind thresholds calibrated against the
default backend keep meaning the same thing here.
"""
import logging

import trafilatura

from ..beautiful_soup.beautiful_soup import BeautifulSoupScraper

logger = logging.getLogger(__name__)


class TrafilaturaScraper(BeautifulSoupScraper):
    """Same fetch and same features as the default backend, trafilatura's text.

    trafilatura decides which part of the document is the main content and drops
    recurring chrome (navigation, category lists, recommendation blocks). It
    returns nothing when it finds no main content at all, which is reported as
    empty text rather than papered over with the whole page.
    """

    def _text_from(self, response, document_text) -> str:
        extracted = trafilatura.extract(
            response.content,
            url=self.link,
            output_format="txt",
            include_comments=False,
            include_tables=True,
        )
        if not extracted:
            logger.warning(f"trafilatura found no main content for {self.link}")
            return ""
        return extracted
