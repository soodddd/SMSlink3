#!/usr/bin/env python3
"""Minimal host relay for Android emulator discovery QA.

The app posts emulator identities to http://10.0.2.2:1816/register and fetches
peers from /peers?deviceId=... because emulator UDP broadcast is isolated.
This relay is test infrastructure only; it must not be shipped in app code.
"""

from __future__ import annotations

import json
import argparse
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


PEERS: dict[str, dict] = {}
TTL_SECONDS = 15


class RelayHandler(BaseHTTPRequestHandler):
    server_version = "SmsLinkEmulatorRelay/1.0"

    def do_POST(self) -> None:
        if self.path != "/register":
            self.send_error(404)
            return

        length = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(length)
        try:
            identity = json.loads(payload.decode("utf-8"))
            device_id = identity["deviceId"]
        except Exception:
            self.send_error(400, "invalid identity")
            return

        identity["_seenAt"] = time.time()
        PEERS[device_id] = identity
        self._send_json({"ok": True, "count": len(PEERS)})

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/peers":
            self.send_error(404)
            return

        requester = parse_qs(parsed.query).get("deviceId", [""])[0]
        now = time.time()
        stale = [device_id for device_id, peer in PEERS.items() if now - peer.get("_seenAt", 0) > TTL_SECONDS]
        for device_id in stale:
            PEERS.pop(device_id, None)

        peers = [
            {key: value for key, value in peer.items() if key != "_seenAt"}
            for device_id, peer in PEERS.items()
            if device_id != requester
        ]
        self._send_json(peers)

    def log_message(self, format: str, *args) -> None:
        return

    def _send_json(self, payload) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=1816)
    args = parser.parse_args()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), RelayHandler)
    print(f"SMS-link emulator relay listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
