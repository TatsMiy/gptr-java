import os
import requests
import tempfile
from urllib.parse import urlparse
from langchain_community.document_loaders import PyMuPDFLoader

from ..outcome import http_status_reason, mark_fetch_failure


class PyMuPDFScraper:

    def __init__(self, link, session=None):
        """
        Initialize the scraper with a link and an optional session.

        Args:
          link (str): The URL or local file path of the PDF document.
          session (requests.Session, optional): An optional session for making HTTP requests.
        """
        self.link = link
        self.session = session

    def is_url(self) -> bool:
        """
        Check if the provided `link` is a valid URL.

        Returns:
          bool: True if the link is a valid URL, False otherwise.
        """
        try:
            result = urlparse(self.link)
            return all([result.scheme, result.netloc])  # Check for valid scheme and network location
        except Exception:
            return False

    def scrape(self, outcome=None) -> tuple[str, list[str], str]:
        """
        The `scrape` function uses PyMuPDFLoader to load a document from the provided link (either URL or local file)
        and returns the document as a string.

        Args:
          outcome: Optional ScrapeOutcome to fill with the transport facts
            measured here. Without one the method behaves exactly as before.

        Returns:
          str: A string representation of the loaded document.
        """
        try:
            if self.is_url():
                http = self.session or requests
                try:
                    response = http.get(self.link, timeout=(5, 30), stream=True)
                    response.raise_for_status()
                except requests.exceptions.SSLError:
                    import logging
                    logging.getLogger(__name__).warning(
                        f"SSL verification failed for {self.link}, retrying without verification"
                    )
                    response = http.get(self.link, timeout=(5, 30), stream=True, verify=False)
                    response.raise_for_status()

                if outcome is not None:
                    outcome.fetched = True
                    outcome.http_status = response.status_code

                downloaded_bytes = 0
                with tempfile.NamedTemporaryFile(delete=False, suffix=".pdf") as temp_file:
                    temp_filename = temp_file.name  # Get the temporary file name
                    for chunk in response.iter_content(chunk_size=8192):
                        temp_file.write(chunk)  # Write the downloaded content to the temporary file
                        downloaded_bytes += len(chunk)

                if outcome is not None:
                    outcome.bytes = downloaded_bytes

                # Always clean up the downloaded temp file, even if loading fails
                # (PyMuPDFLoader.load() can raise on a malformed/partial PDF).
                try:
                    loader = PyMuPDFLoader(temp_filename)
                    doc = loader.load()
                finally:
                    try:
                        os.remove(temp_filename)
                    except OSError:
                        pass
            else:
                loader = PyMuPDFLoader(self.link)
                doc = loader.load()

            # Extract the content, image (if any), and title from the document.
            image = []
            # Retrieve content from ALL pages to ensure PDFs with cover pages pass validation.
            content = "\n".join(page.page_content for page in doc)
            title = doc[0].metadata.get("title", "") if doc else ""
            if outcome is not None:
                outcome.text_chars = len(content)
            return content, image, title

        except requests.exceptions.HTTPError as e:
            status = getattr(e.response, "status_code", None)
            print(f"Error loading PDF : {self.link} {e}")
            mark_fetch_failure(
                outcome,
                http_status_reason(status) if status else "exception",
                http_status=status,
                fetched=status is not None,
            )
            return "", [], ""
        except requests.exceptions.Timeout:
            print(f"Download timed out. Please check the link : {self.link}")
            mark_fetch_failure(outcome, "timeout")
            return "", [], ""
        except Exception as e:
            print(f"Error loading PDF : {self.link} {e}")
            mark_fetch_failure(outcome, "exception")
            return "", [], ""
