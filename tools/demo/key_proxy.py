#!/usr/bin/env python3
"""A loopback relay from the emulator to the DeepSeek API, for the demo recordings (.github/workflows/demo-recordings.yml).

The app on the emulator talks to http://10.0.2.2:8080/v1 (the runner's loopback) with no API key, exactly as it talked
to the llama.cpp server before. This relay adds the Authorization header and forwards each request unchanged to
https://api.deepseek.com, streaming the answer back as it arrives. The key therefore never enters the emulator: it is
not in the app's storage, its requests, logcat, screenshots or screen recordings.

The key is read from the DEEPSEEK_API_KEY environment variable of this process only. It is never printed or written
anywhere: the log holds the method, path, status, timing and token counts, never headers or bodies. Error bodies from
the API are replaced by a short message with anything key-like removed, because some APIs quote part of the key.

Usage: DEEPSEEK_API_KEY=... python3 tools/demo/key_proxy.py [port]
"""

import http.client
import http.server
import json
import os
import re
import socketserver
import sys
import time

UPSTREAM_HOST = "api.deepseek.com"
# Tests only: a plain-HTTP stand-in for the API, e.g. "127.0.0.1:9999".
TEST_UPSTREAM = os.environ.get("DEMO_TEST_UPSTREAM", "")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
KEY = os.environ.get("DEEPSEEK_API_KEY", "").strip()
# Hop-by-hop and auth headers are never passed through.
DROP = {"host", "authorization", "connection", "keep-alive", "proxy-authorization", "te", "trailer", "transfer-encoding",
        "upgrade", "content-length", "accept-encoding", "x-api-key", "api-key"}
KEYLIKE = re.compile(r"(sk-[A-Za-z0-9_-]{4,}|\*{2,}[A-Za-z0-9]{2,}|[A-Za-z0-9]{32,})")


def log(msg):
    print(f"[proxy {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def scrub(text):
    out = KEYLIKE.sub("[redacted]", text)
    if KEY:
        out = out.replace(KEY, "[redacted]")
    return out


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # the default logs the request line only, but keep every line deliberate
        pass

    def _upstream_path(self):
        p = self.path
        # The app uses OpenAI-style paths under /v1; DeepSeek serves both /v1/... and /....
        return p[3:] if p.startswith("/v1/") else p

    def _relay(self, method):
        started = time.time()
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in DROP}
        headers["Authorization"] = "Bearer " + KEY
        headers["Accept-Encoding"] = "identity"
        model = ""
        if body:
            try:
                model = json.loads(body).get("model", "")
            except Exception:
                pass
        conn = (http.client.HTTPConnection(TEST_UPSTREAM, timeout=600) if TEST_UPSTREAM
                else http.client.HTTPSConnection(UPSTREAM_HOST, timeout=600))
        try:
            conn.request(method, self._upstream_path(), body=body, headers=headers)
            resp = conn.getresponse()
        except Exception as e:
            log(f"{method} {self.path} upstream error {type(e).__name__}")
            self._send_error(502, f"DeepSeek could not be reached ({type(e).__name__})")
            return
        if resp.status >= 400:
            detail = scrub(resp.read().decode(errors="replace"))[:300]
            log(f"{method} {self.path} model={model} -> HTTP {resp.status} {detail!r} in {time.time() - started:.1f}s")
            self._send_error(resp.status, f"DeepSeek returned HTTP {resp.status}: {detail}")
            conn.close()
            return
        self.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() not in DROP:
                self.send_header(k, v)
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Connection", "close")
        self.end_headers()
        total = 0
        usage = None
        first = None
        tail = b""
        try:
            while True:
                chunk = resp.read1(65536) if hasattr(resp, "read1") else resp.read(4096)
                if not chunk:
                    break
                if first is None:
                    first = time.time() - started
                total += len(chunk)
                tail = (tail + chunk)[-8192:]
                self.wfile.write(f"{len(chunk):X}\r\n".encode() + chunk + b"\r\n")
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            log(f"{method} {self.path} client went away after {total} bytes")
        finally:
            conn.close()
        for m in re.finditer(rb'"usage"\s*:\s*(\{[^{}]*\})', tail):
            try:
                usage = json.loads(m.group(1))
            except Exception:
                pass
        u = f" tokens in/out {usage.get('prompt_tokens')}/{usage.get('completion_tokens')}" if usage else ""
        ttfb = f" first byte {first:.1f}s" if first is not None else ""
        log(f"{method} {self.path} model={model} -> {resp.status} {total} B{u}{ttfb} total {time.time() - started:.1f}s")
        self.close_connection = True

    def _send_error(self, status, message):
        data = json.dumps({"error": {"message": message, "type": "relay_error", "code": status}}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def do_GET(self):
        if self.path in ("/health", "/v1/health"):
            data = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self._relay("GET")

    def do_POST(self):
        self._relay("POST")


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    if not KEY:
        log("DEEPSEEK_API_KEY is empty; not starting")
        sys.exit(2)
    srv = Server(("127.0.0.1", PORT), Handler)
    log(f"relaying 127.0.0.1:{PORT} to https://{UPSTREAM_HOST} (key injected here, never logged)")
    srv.serve_forever()


if __name__ == "__main__":
    main()
