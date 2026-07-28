import json
import logging

from odoo.http import request

_logger = logging.getLogger(__name__)

MAX_BODY_BYTES = 64 * 1024


def client_ip():
    forwarded = request.httprequest.environ.get("HTTP_X_FORWARDED_FOR", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.httprequest.remote_addr or "unknown"


def body_json():
    """Parsed JSON body, or None if the request exceeds the size limit."""
    raw = request.httprequest.get_data(as_text=False) or b""
    if len(raw) > MAX_BODY_BYTES:
        return None
    try:
        return json.loads(raw.decode("utf-8") or "{}")
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}


def idempotency_key(body=None):
    key = request.httprequest.headers.get("X-Idempotency-Key") or (body or {}).get("idempotency_key")
    return str(key).strip()[:64] if key else False


def request_id(body=None):
    value = request.httprequest.headers.get("X-Request-Id") or (body or {}).get("request_id")
    return str(value).strip()[:64] if value else False


def rate_limited(bucket, identifier, limit, window_seconds):
    """Returns True if the caller is still within the limit."""
    allowed = request.env["food.rate.limit"].sudo()._hit(bucket, identifier, limit, window_seconds)
    if not allowed:
        _logger.warning("food_ordering: rate limit hit bucket=%s identifier=%s", bucket, identifier)
    return allowed
