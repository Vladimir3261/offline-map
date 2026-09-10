#!/usr/bin/env python3
"""Simulated firmware odometer JSON/SSE API; no OBD connection or dependencies."""
import argparse
import hmac
import json
import math
import os
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--speed-kmh", type=int, default=50)
    parser.add_argument("--odometer-m", type=int, default=37104)
    parser.add_argument("--initial-delay", type=float, default=0)
    parser.add_argument("--no-stream", action="store_true")
    parser.add_argument("--disconnected", action="store_true")
    parser.add_argument("--unavailable", action="store_true")
    args = parser.parse_args()
    if not 0 <= args.speed_kmh <= 300 or args.odometer_m < 0:
        parser.error("speed must be 0..300 km/h and odometer must be nonnegative integer metres")
    if not math.isfinite(args.initial_delay) or args.initial_delay < 0:
        parser.error("initial delay must be finite and nonnegative")
    token = os.environ.get("ROUTEODO_MOCK_TOKEN", "")
    epoch = uuid.uuid4().hex[:8]
    started = time.monotonic()
    start_utc = time.time()

    def sample():
        elapsed = time.monotonic() - started
        now = start_utc + elapsed
        ready = elapsed >= args.initial_delay
        moving = max(0, elapsed - args.initial_delay) if not args.disconnected else 0
        anchor_seconds = math.floor(moving)
        speed_seconds = math.floor(moving * 10) / 10
        origin_time = start_utc + args.initial_delay
        return {
            "t": now,
            "epoch": epoch,
            "odometer_m": args.odometer_m + math.floor(anchor_seconds * args.speed_kmh / 3.6) if ready else None,
            "odometer_t": origin_time + anchor_seconds if ready else None,
            "speed_kmh": args.speed_kmh if ready else None,
            "speed_t": origin_time + speed_seconds if ready else None,
            "connected": not args.disconnected,
            "clock_synced": True,
            "resets": 0,
            "source": "mock_distance",
            "mapping_ver": 5,
        }

    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, value):
            body = json.dumps(value, separators=(",", ":")).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            try:
                self.handle_get()
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                pass

        def handle_get(self):
            if self.path not in ("/api/odometer", "/api/odometer/stream"):
                self.reply(404, {"error": "not found"})
                return
            authorization = self.headers.get("Authorization", "").encode()
            if token and not hmac.compare_digest(authorization, f"Bearer {token}".encode()):
                self.reply(401, {"error": "unauthorized"})
                return
            if args.unavailable:
                self.reply(503, {"error": "odometer unavailable"})
                return
            if self.path == "/api/odometer":
                self.reply(200, sample())
                return
            if args.no_stream:
                self.reply(404, {"error": "stream unavailable"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            last_comment = time.monotonic()
            while True:
                self.wfile.write(b"data: " + json.dumps(sample(), separators=(",", ":")).encode() + b"\n\n")
                if time.monotonic() - last_comment >= 15:
                    self.wfile.write(b": keepalive\n\n")
                    last_comment = time.monotonic()
                self.wfile.flush()
                time.sleep(0.1)

        def log_message(self, *_):
            pass  # Do not log request headers or credentials.

    print(f"Mock API: http://{args.host}:{args.port}/api/odometer", flush=True)
    print(f"Simulating {args.speed_kmh} km/h from {args.odometer_m} m; epoch {epoch}. Start navigation in the app.", flush=True)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
