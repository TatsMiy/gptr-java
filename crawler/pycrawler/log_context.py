"""Request correlation id, carried into every log record.

The Java engine sends ``X-Request-Id`` on each call. Stamping it onto every log
record is what makes a crawler log line attributable to the task that produced
it, instead of leaving log and task timelines unmatchable.
"""
import logging
from contextvars import ContextVar

REQUEST_ID_HEADER = "X-Request-Id"
NO_REQUEST_ID = "-"

LOG_FORMAT = "%(asctime)s %(levelname)s [%(request_id)s] %(name)s: %(message)s"

_request_id: ContextVar[str] = ContextVar("request_id", default=NO_REQUEST_ID)


def set_request_id(value: str | None) -> None:
    _request_id.set(value or NO_REQUEST_ID)


def get_request_id() -> str:
    return _request_id.get()


class RequestIdFilter(logging.Filter):
    """Stamp the current request id onto every record passing through."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = _request_id.get()
        return True


def install_request_id_filter() -> None:
    """Attach the filter to the root handlers, so records from every child
    logger carry the id and the format string can reference it."""
    for handler in logging.getLogger().handlers:
        if not any(isinstance(f, RequestIdFilter) for f in handler.filters):
            handler.addFilter(RequestIdFilter())
        handler.setFormatter(logging.Formatter(LOG_FORMAT))
