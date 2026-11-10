import asyncio
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest

from onn_session_policy.adapters import AdapterError, Android, SessionClient, broadcast_json, read_token, store_token
from onn_session_policy.controller import Config, Controller, control_request
from onn_session_policy.policy import Phase


TOKEN = "1234" * 16


class LocalServer:
    def __enter__(self):
        owner = self
        self.events = []
        self.failures = set()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    self.send_error(401)
                    return
                if self.path in owner.failures:
                    self.send_error(503)
                    return
                self.answer({"schema":1, "session":{"id":None,"video_id":None,
                    "phase":"inactive","expires_at_ms":0,"duration_seconds":None,"position_seconds":None},
                    "native_lease_status":"stopped", "foreground_package":None,
                    "rollout":"prepared", "session_mode":"scheduled", "authentication":"unknown"})

            def do_POST(self):
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    self.send_error(401)
                    return
                size = int(self.headers.get("Content-Length", "0"))
                if size > 4096:
                    self.send_error(413)
                    return
                owner.events.append(json.loads(self.rfile.read(size)))
                self.answer({"schema":1,"result":"ok"})

            def answer(self, value):
                body = json.dumps(value).encode()
                self.send_response(200)
                self.send_header("Content-Type","application/json")
                self.send_header("Content-Length",str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1",0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_port
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class AdapterTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_loopback_http_auth_status_event_and_error(self):
        with LocalServer() as server:
            client = SessionClient(TOKEN, server.port)
            snapshot = await client.status()
            self.assertEqual(Phase.INACTIVE, snapshot.session.phase)
            event = {"type":"phone_key","linux_code":116,"value":1,"wall_ms":1}
            await client.event(event)
            self.assertEqual(event, server.events[-1])
            with self.assertRaises(AdapterError):
                await SessionClient("wrong", server.port).status()
            server.failures.add("/status")
            with self.assertRaises(AdapterError):
                await client.status()
            with self.assertRaises(AdapterError):
                await client.event({"oversized":"x" * 4096})

    async def test_real_controller_inactive_start_status_and_owned_shutdown(self):
        with tempfile.TemporaryDirectory() as directory, LocalServer() as server:
            config = Config(Path(directory), "/data/local/tmp/onn_input_guard")
            # Passive startup may attempt this unavailable executable; it cannot
            # reach a real Android device or activate the prepared session.
            controller = Controller(config, Android("/nonexistent/no-adb"), SessionClient(TOKEN,server.port))
            task = asyncio.create_task(controller.run())
            for _ in range(100):
                if task.done():
                    try:
                        task.result()
                    except PermissionError as exc:
                        if exc.errno == 1:
                            self.skipTest("host policy forbids AF_UNIX; Termux socket smoke test required")
                        raise
                if (Path(directory) / "control.sock").exists():
                    break
                await asyncio.sleep(0.01)
            state = await control_request(directory, "status")
            self.assertEqual("running", state["controller"])
            self.assertIn(state["native_process"], ("stopped", "failed"))
            result = await control_request(directory, "shutdown")
            self.assertEqual("stopping", result["controller"])
            await asyncio.wait_for(task, 3)
            self.assertFalse((Path(directory)/"control.sock").exists())
            self.assertIn({"type":"native_health","status":"stopped"}, server.events)


class TokenTests(unittest.TestCase):
    def test_token_saved_atomically_private_and_insecure_file_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            token_file = Path(directory)/"controller.token"
            store_token(token_file, TOKEN)
            self.assertEqual(0o600, token_file.stat().st_mode & 0o777)
            self.assertEqual(TOKEN, read_token(token_file))
            token_file.chmod(0o644)
            with self.assertRaises(AdapterError):
                read_token(token_file)
            token_file.unlink()
            token_file.symlink_to(Path(directory)/"missing")
            with self.assertRaises(OSError):
                read_token(token_file)

    def test_am_broadcast_result_data_parses_without_printing_secret(self):
        result = '{"schema":1,"token":"' + TOKEN + '","port":8768}'
        for output in ('Broadcast completed: result=0, data="' + result + '"',
                       'Broadcast completed: result=0, data="' + result.replace('"','\\"') + '"'):
            parsed = broadcast_json(output)
            self.assertEqual(TOKEN, parsed["token"])
        with self.assertRaises(AdapterError):
            broadcast_json("Broadcast failed")


if __name__ == "__main__":
    unittest.main()
