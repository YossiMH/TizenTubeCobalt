#!/usr/bin/env python3
"""Temporary, scoped LAN exchange for two APKs; no credential or arbitrary file API."""
import argparse
import hashlib
import hmac
import http.server
import json
from pathlib import Path
import re
import socketserver
import sys
import time

ROOT = None
TOKEN = None
DEADLINE = 0
GET_FILES = {'/morning-unsigned.apk': 'morning-unsigned.apk', '/guard-unsigned.apk': 'guard-unsigned.apk', '/sign-two-apks.ps1': 'sign-two-apks.ps1'}
POST_FILES = {'/signed/morning.apk': 'morning-signed.apk', '/signed/guard.apk': 'guard-signed.apk'}
MAX_BYTES = 524288


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def authorized(self):
        return (time.monotonic() < DEADLINE and hmac.compare_digest(
            self.headers.get('Authorization', ''), 'Bearer ' + TOKEN))

    def reply(self, status, body=b'', content_type='application/json'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self.authorized():
            return self.reply(403)
        filename = GET_FILES.get(self.path)
        if filename is None:
            return self.reply(404)
        path = ROOT / filename
        if not path.is_file() or path.stat().st_size > MAX_BYTES:
            return self.reply(404)
        self.reply(200, path.read_bytes(), 'application/vnd.android.package-archive')

    def do_POST(self):
        if not self.authorized():
            return self.reply(403)
        filename = POST_FILES.get(self.path)
        if filename is None:
            return self.reply(404)
        try:
            length = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            return self.reply(400)
        if not 0 < length <= MAX_BYTES or self.headers.get('Transfer-Encoding'):
            return self.reply(413)
        path = ROOT / filename
        if path.exists():
            return self.reply(409)
        self.connection.settimeout(10)
        data = self.rfile.read(length)
        if len(data) != length or not data.startswith(b'PK\x03\x04'):
            return self.reply(400)
        with path.open('xb') as stream:
            stream.write(data)
        path.chmod(0o600)
        result = {'ok': True, 'file': filename, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        (ROOT / (filename + '.json')).write_text(json.dumps(result, sort_keys=True))
        self.reply(201, json.dumps(result).encode())


class Server(socketserver.TCPServer):
    allow_reuse_address = False


def main():
    global ROOT, TOKEN, DEADLINE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True,
                        help='private staging directory outside the source checkout')
    parser.add_argument('--bind', required=True, help='explicit authorized LAN bind address')
    parser.add_argument('--port', type=int, default=8769)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('invalid port')
    ROOT = args.directory.resolve(strict=True)
    if not ROOT.is_dir():
        parser.error('staging directory is required')
    TOKEN = (ROOT / 'exchange.token').read_text().strip()
    if not re.fullmatch(r'[0-9a-f]{64}', TOKEN):
        raise SystemExit('invalid exchange token')
    DEADLINE = time.monotonic() + 900
    with Server((args.bind, args.port), Handler) as server:
        server.timeout = 1
        (ROOT / 'exchange.ready.json').write_text(json.dumps({
            'pid': __import__('os').getpid(), 'expires_in_seconds': 900}))
        while time.monotonic() < DEADLINE:
            server.handle_request()
            if all((ROOT / f).is_file() for f in POST_FILES.values()):
                break
        print('signing_exchange_closed', flush=True)


if __name__ == '__main__':
    main()
