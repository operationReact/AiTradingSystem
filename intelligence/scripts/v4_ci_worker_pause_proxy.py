"""Runner-local pause gate for V69 real-worker safety tests.

Bound ONLY to 127.0.0.1. Forwards validated model requests to the
separately running synthetic FastAPI worker; blocks only while explicitly
armed by JUnit so an ADMIN pause can happen after V61 reservations commit.

Not imported by application code; no remote configuration or broker access.
"""
from __future__ import annotations

import http.client
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

lock = threading.Lock()
released = threading.Event()
released.set()
state = {"armed": False, "held": 0}


class LocalGate(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: object) -> None:
        # Never log authorization tokens, inference bodies or model outputs.
        pass

    def send_json(self, status: int, payload: dict[str, object]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(200, {"status": "UP", "synthetic_only": True})
        elif self.path == "/ci/held":
            with lock:
                held = state["held"]
            self.send_json(200, {"held": held})
        else:
            self.send_json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path == "/ci/arm":
            released.clear()
            with lock:
                state["armed"] = True
                state["held"] = 0
            self.send_json(200, {"armed": True})
            return
        if self.path == "/ci/release":
            with lock:
                state["armed"] = False
            released.set()
            self.send_json(200, {"released": True})
            return
        if self.path != "/v1/research/artifact-inference":
            self.send_json(404, {"error": "not_found"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_json(400, {"error": "invalid_length"})
            return
        if not 0 < length <= 16_384:
            self.send_json(413, {"error": "out_of_bounds"})
            return
        payload = self.rfile.read(length)

        with lock:
            pause = state["armed"]
            if pause:
                state["held"] += 1
        if pause and not released.wait(timeout=8):
            self.send_json(504, {"error": "synthetic_gate_timeout"})
            return

        upstream = http.client.HTTPConnection("127.0.0.1", 18721, timeout=8)
        try:
            # Forward only allowed headers; no arbitrary upstream URI, redirects,
            # cookies, authorization metadata or user-supplied hosts.
            forwarded = {
                k: v for k, v in self.headers.items()
                if k.lower() in {"content-type", "accept", "x-research-worker-token"}
            }
            upstream.request("POST", self.path, body=payload, headers=forwarded)
            response = upstream.getresponse()
            body = response.read(32_769)
            if len(body) > 32_768:
                self.send_json(502, {"error": "oversized_worker_response"})
                return
            self.send_response(response.status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (OSError, http.client.HTTPException):
            self.send_json(503, {"error": "synthetic_worker_unavailable"})
        finally:
            upstream.close()


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 18722), LocalGate).serve_forever()
