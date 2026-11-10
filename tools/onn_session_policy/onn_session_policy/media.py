"""Optional, read-only Cobalt CDP media observation; no player mutation."""
from enum import Enum
import http.client
import ipaddress
import json
import math
import re
import time
from urllib.parse import urlparse, urlunparse

from .adapters import AdapterError, MAX_HTTP_BODY
from .policy import OBSERVATION_MAX_AGE_MS, Playback, Playing


MEDIA_EXPRESSION = """JSON.stringify((function(){var a=window.__ttAllowedOnly,s=a&&a.state,m=s&&s.morningMedia;return m&&a.routeVideoId()===s.morningTrackedVideo?{video_id:s.morningTrackedVideo,position_seconds:Number(m.currentTime),duration_seconds:Number(m.duration),playing:m.ended?'ended':m.paused?'paused':'playing',sample_wall_ms:Date.now()}:null})())"""


class MediaSource(str, Enum):
    AUTO = "auto"
    DIAL = "dial"
    CDP = "cdp"


def loopback_websocket(value, port=9222):
    if not isinstance(value, str) or len(value) > 2048:
        return None
    try:
        parsed = urlparse(value)
        if parsed.scheme not in ("ws", "wss") or parsed.username is not None or parsed.password is not None or parsed.fragment:
            return None
        host = parsed.hostname
        if host == "localhost":
            host = "127.0.0.1"
        if host is None or not ipaddress.ip_address(host).is_loopback or parsed.port != port:
            return None
        address = "[" + host + "]" if ":" in host else host
        return urlunparse(parsed._replace(netloc=f"{address}:{port}"))
    except ValueError:
        return None


def select_cobalt_target(targets, port=9222):
    if not isinstance(targets, list):
        return None
    selected = []
    for target in targets:
        if not isinstance(target, dict) or target.get("type", "page") != "page":
            continue
        try:
            page = urlparse(target.get("url", ""))
            if page.scheme not in ("http", "https") or page.hostname not in ("youtube.com", "www.youtube.com"):
                continue
            if page.path != "/tv" and not page.path.startswith("/tv/"):
                continue
            websocket_url = loopback_websocket(target.get("webSocketDebuggerUrl"), port)
            if websocket_url:
                selected.append(websocket_url)
        except (ValueError, TypeError):
            pass
    return selected[0] if len(selected) == 1 else None


def parse_media_value(value, wall_ms):
    if isinstance(value, str):
        if len(value) > MAX_HTTP_BODY:
            return None
        try:
            value = json.loads(value)
        except ValueError:
            return None
    if not isinstance(value, dict):
        return None
    video = value.get("video_id")
    position, duration = value.get("position_seconds"), value.get("duration_seconds")
    sampled = value.get("sample_wall_ms")
    if not isinstance(video, str) or not re.fullmatch(r"[A-Za-z0-9_-]{11}", video):
        return None
    if type(position) not in (int, float) or not math.isfinite(position) or position < 0:
        return None
    if duration is not None and (type(duration) not in (int, float) or not math.isfinite(duration) or duration <= 0):
        return None
    if type(sampled) is not int or not -1000 <= wall_ms - sampled <= OBSERVATION_MAX_AGE_MS:
        return None
    try:
        playing = Playing(value.get("playing"))
    except (TypeError, ValueError):
        return None
    return Playback(video, float(position), playing, sampled, float(duration) if duration is not None else None)


def parse_cdp_response(response, request_id, wall_ms):
    try:
        if isinstance(response, str):
            if len(response) > MAX_HTTP_BODY:
                return None
            response = json.loads(response)
        if not isinstance(response, dict) or response.get("id") != request_id or "error" in response:
            return None
        result = response["result"]
        if "exceptionDetails" in result:
            return None
        value = result["result"]
        if value.get("type") != "string":
            return None
        return parse_media_value(value.get("value"), wall_ms)
    except (KeyError, TypeError, ValueError):
        return None


class CobaltMediaReader:
    """Reuse one loopback debugger socket; each entire sample has a 2s budget."""
    def __init__(self, port=9222, timeout=2.0):
        self.port, self.timeout = port, timeout
        self._socket = None
        self._url = None
        self._request_id = 0

    @staticmethod
    def _remaining(deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise AdapterError("cdp_timeout")
        return remaining

    def _discover(self, deadline):
        for path in ("/json", "/json/list"):
            connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=self._remaining(deadline))
            try:
                connection.request("GET", path, headers={"Connection": "close"})
                response = connection.getresponse()
                body = response.read(MAX_HTTP_BODY + 1)
                if response.status == 200 and len(body) <= MAX_HTTP_BODY:
                    selected = select_cobalt_target(json.loads(body), self.port)
                    if selected is not None:
                        return selected
            except (OSError, ValueError, http.client.HTTPException):
                pass
            finally:
                connection.close()
        raise AdapterError("cdp_target_unavailable")

    def sample(self, wall_ms=None):
        try:
            import websocket  # Optional pinned websocket-client, never a model.
        except ImportError as exc:
            raise AdapterError("cdp_dependency_unavailable") from exc
        deadline = time.monotonic() + self.timeout
        try:
            if self._url is None:
                self._url = self._discover(deadline)
            if self._socket is None:
                self._socket = websocket.create_connection(self._url,
                    timeout=self._remaining(deadline), suppress_origin=True,
                    http_no_proxy=["*"], redirect_limit=0)
            self._request_id += 1
            request_id = self._request_id
            self._socket.settimeout(self._remaining(deadline))
            self._socket.send(json.dumps({"id": request_id, "method": "Runtime.evaluate", "params": {
                "expression": MEDIA_EXPRESSION, "returnByValue": True}}))
            while True:
                self._socket.settimeout(self._remaining(deadline))
                raw = self._socket.recv()
                if not isinstance(raw, str) or len(raw) > MAX_HTTP_BODY:
                    raise AdapterError("cdp_response_invalid")
                response = json.loads(raw)
                if isinstance(response, dict) and response.get("id") == request_id:
                    current = time.time_ns() // 1_000_000 if wall_ms is None else wall_ms
                    return parse_cdp_response(response, request_id, current)
        except (OSError, ValueError, AdapterError, websocket.WebSocketException) as exc:
            self.close()
            self._url = None
            raise AdapterError("cdp_unavailable") from exc

    def close(self):
        if self._socket is not None:
            try:
                self._socket.close(timeout=0)
            except (OSError, TypeError):
                pass
            self._socket = None
