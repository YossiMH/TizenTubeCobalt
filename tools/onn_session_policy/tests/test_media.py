import json
import unittest

from onn_session_policy.media import loopback_websocket, parse_cdp_response, parse_media_value, select_cobalt_target
from onn_session_policy.policy import Playing


NOW = 1_800_000_000_000


def value(playing="playing"):
    return {"video_id":"abcdefghijk", "position_seconds":20,
            "duration_seconds":20, "playing":playing, "sample_wall_ms":NOW}


class MediaTests(unittest.TestCase):
    def test_only_explicit_media_ended_means_ended(self):
        self.assertEqual(Playing.PLAYING, parse_media_value(value(), NOW).playing)
        self.assertEqual(Playing.PAUSED, parse_media_value(value("paused"), NOW).playing)
        self.assertEqual(Playing.ENDED, parse_media_value(value("ended"), NOW).playing)
        raw = {"id":7,"result":{"result":{"type":"string", "value":json.dumps(value("ended"))}}}
        parsed = parse_cdp_response(raw, 7, NOW)
        self.assertEqual(20, parsed.duration_seconds)
        self.assertEqual(Playing.ENDED, parsed.playing)
        self.assertIsNone(parse_cdp_response(raw, 8, NOW))

    def test_invalid_and_stale_media_is_rejected(self):
        for key, invalid in (("position_seconds",float("nan")), ("duration_seconds",float("inf")),
                             ("duration_seconds",-1), ("sample_wall_ms",NOW-5001),
                             ("sample_wall_ms",NOW+1001), ("playing","idle"),
                             ("video_id","bad")):
            sample = value()
            sample[key] = invalid
            self.assertIsNone(parse_media_value(sample, NOW))
        self.assertIsNone(parse_media_value("null", NOW))

    def test_debugger_target_is_unique_youtube_tv_and_loopback_only(self):
        target = {"type":"page", "url":"https://www.youtube.com/tv#/watch?v=abcdefghijk",
                  "webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/0"}
        self.assertEqual(target["webSocketDebuggerUrl"], select_cobalt_target([target]))
        self.assertIsNone(select_cobalt_target([target,target]))
        for url in ("https://evil.example/tv", "https://youtube.com.evil.example/tv", "https://youtube.com/watch?v=abcdefghijk"):
            self.assertIsNone(select_cobalt_target([{**target,"url":url}]))
        for url in ("ws://192.168.1.8:9222/x", "ws://evil.example:9222/x", "file:///tmp/x",
                    "ws://127.0.0.1:8768/x", "ws://user:password@127.0.0.1:9222/x", "ws://127.0.0.1:9222/x#evil"):
            self.assertIsNone(loopback_websocket(url))
        self.assertEqual("ws://127.0.0.1:9222/x", loopback_websocket("ws://localhost:9222/x"))


if __name__ == "__main__":
    unittest.main()
