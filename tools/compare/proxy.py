#!/usr/bin/env python3
"""Logging pass-through proxy between an app on the emulator and llama-server on the runner.

Used by the one-to-one comparison (tools/compare/run.py, .github/workflows/compare.yml). Both apps talk to the same
model through this proxy, so every request is measured the same way, whatever the app does inside:

- one JSON line per chat request in the log file: which app (the current label), when it started and ended, the
  messages and tools it carried, the request size, the tool calls and text the model answered with, and the
  server's own token counts (usage and timings);
- while the server is still reading a long prompt, a streamed request gets SSE comment lines (": waiting") every
  few seconds. SSE clients ignore comments; they only stop an app's read timeout from firing during a slow CPU
  prefill, which would otherwise measure the runner's CPU instead of the app. Nothing else in the traffic changes.

Control endpoints (from the test driver, never from an app): POST /__label with the label as body, GET /__log.

Usage: proxy.py <listen port> <upstream base, e.g. http://127.0.0.1:8080> <log file>
"""

import http.client
import json
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN = int(sys.argv[1])
UPSTREAM = urllib.parse.urlparse(sys.argv[2])
LOG = sys.argv[3]
LABEL = {"value": "unlabelled"}
LOCK = threading.Lock()
KEEPALIVE_S = 5
HOP = {"connection", "keep-alive", "transfer-encoding", "content-length", "proxy-connection", "upgrade", "te", "trailer"}


def write_log(entry):
    with LOCK:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def summarize_request(body):
    try:
        req = json.loads(body.decode("utf-8"))
    except Exception:
        return {"unparsed": True}
    msgs = req.get("messages") or []
    tools = req.get("tools") or []
    last_user = ""
    for m in reversed(msgs):
        if m.get("role") == "user":
            c = m.get("content")
            if isinstance(c, list):
                c = " ".join(p.get("text", "") for p in c if isinstance(p, dict))
            last_user = str(c)[:300]
            break
    system = ""
    if msgs and msgs[0].get("role") == "system":
        c = msgs[0].get("content")
        system = c if isinstance(c, str) else json.dumps(c)
    return {
        "model": req.get("model"),
        "stream": bool(req.get("stream")),
        "messages": len(msgs),
        "roles": [m.get("role") for m in msgs][-12:],
        "last_user": last_user,
        "system_chars": len(system),
        "tools": len(tools),
        "tool_names": [t.get("function", {}).get("name") for t in tools][:200],
        "tool_schema_chars": len(json.dumps(tools)),
        "request_bytes": len(body),
    }


class Answer:
    """Accumulates a streamed or plain chat completion."""

    def __init__(self):
        self.text = []
        self.calls = {}
        self.finish = None
        self.usage = None
        self.timings = None
        self.error = None

    def feed_obj(self, obj):
        if "error" in obj:
            self.error = str(obj["error"])[:500]
        if obj.get("usage"):
            self.usage = obj["usage"]
        if obj.get("timings"):
            self.timings = obj["timings"]
        for ch in obj.get("choices") or []:
            part = ch.get("delta") or ch.get("message") or {}
            if part.get("content"):
                self.text.append(part["content"])
            for tc in part.get("tool_calls") or []:
                idx = tc.get("index", len(self.calls))
                cur = self.calls.setdefault(idx, {"name": "", "arguments": ""})
                fn = tc.get("function") or {}
                if fn.get("name"):
                    cur["name"] += fn["name"]
                if fn.get("arguments"):
                    cur["arguments"] += fn["arguments"] if isinstance(fn["arguments"], str) else json.dumps(fn["arguments"])
            if ch.get("finish_reason"):
                self.finish = ch["finish_reason"]

    def feed_sse_line(self, line):
        line = line.strip()
        if not line.startswith("data:"):
            return
        data = line[5:].strip()
        if not data or data == "[DONE]":
            return
        try:
            self.feed_obj(json.loads(data))
        except Exception:
            pass

    def result(self):
        return {
            "text": "".join(self.text)[:4000],
            "tool_calls": [{"name": c["name"], "arguments": c["arguments"][:400]} for _, c in sorted(self.calls.items())],
            "finish_reason": self.finish,
            "usage": self.usage,
            "timings": self.timings,
            "error": self.error,
        }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def _send(self, code, data, ctype="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/__log":
            try:
                data = open(LOG, "rb").read()
            except FileNotFoundError:
                data = b""
            return self._send(200, data, "application/x-ndjson")
        self._forward(b"")

    def do_POST(self):
        body = self._body()
        if self.path == "/__label":
            LABEL["value"] = body.decode().strip() or "unlabelled"
            return self._send(200, b"ok")
        self._forward(body)

    def _upstream(self):
        return http.client.HTTPConnection(UPSTREAM.hostname, UPSTREAM.port or 80, timeout=1800)

    def _headers_out(self, body):
        h = {k: v for k, v in self.headers.items() if k.lower() not in HOP and k.lower() != "host"}
        h["Host"] = UPSTREAM.netloc
        if body:
            h["Content-Length"] = str(len(body))
        return h

    def _forward(self, body):
        path = UPSTREAM.path.rstrip("/") + self.path if UPSTREAM.path not in ("", "/") else self.path
        is_chat = self.command == "POST" and self.path.rstrip("/").endswith("/chat/completions")
        entry = {"label": LABEL["value"], "path": self.path, "t_start": time.time()}
        if is_chat:
            entry.update(summarize_request(body))
        stream = is_chat and entry.get("stream")
        conn = self._upstream()
        holder = {}

        def call():
            try:
                conn.request(self.command, path, body=body or None, headers=self._headers_out(body))
                holder["resp"] = conn.getresponse()
            except Exception as e:  # upstream down: reported to the app as a 502
                holder["exc"] = e

        worker = threading.Thread(target=call, daemon=True)
        worker.start()
        answer = Answer()
        try:
            if stream:
                # Headers now, then comments until the server starts answering.
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                waited = 0
                while worker.is_alive():
                    worker.join(KEEPALIVE_S)
                    if worker.is_alive():
                        waited += KEEPALIVE_S
                        self._chunk(b": waiting\n\n")
                entry["keepalive_s"] = waited
                resp = holder.get("resp")
                if resp is None or resp.status != 200:
                    detail = (resp.read().decode(errors="replace") if resp else str(holder.get("exc")))[:800]
                    entry["status"] = resp.status if resp else 502
                    answer.error = detail
                    err = {"error": {"message": f"upstream {entry['status']}: {detail}", "code": entry["status"]}}
                    self._chunk(b"data: " + json.dumps(err).encode() + b"\n\n")
                else:
                    entry["status"] = 200
                    pending = b""
                    while True:
                        data = resp.read1(65536)
                        if not data:
                            break
                        self._chunk(data)
                        pending += data
                        while b"\n" in pending:
                            line, pending = pending.split(b"\n", 1)
                            answer.feed_sse_line(line.decode("utf-8", errors="replace"))
                    if pending:
                        answer.feed_sse_line(pending.decode("utf-8", errors="replace"))
                self._chunk(b"")
            else:
                worker.join()
                resp = holder.get("resp")
                if resp is None:
                    entry["status"] = 502
                    return self._send(502, str(holder.get("exc")).encode())
                data = resp.read()
                entry["status"] = resp.status
                self.send_response(resp.status)
                for k, v in resp.getheaders():
                    if k.lower() not in HOP:
                        self.send_header(k, v)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                if is_chat:
                    try:
                        answer.feed_obj(json.loads(data.decode("utf-8")))
                    except Exception:
                        answer.error = data[:500].decode(errors="replace")
        except (BrokenPipeError, ConnectionResetError) as e:
            entry["client_gone"] = type(e).__name__  # the app hung up (Stop, timeout)
        finally:
            conn.close()
            entry["t_end"] = time.time()
            if is_chat:
                entry.update(answer.result())
                write_log(entry)

    def _chunk(self, data):
        self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
        self.wfile.flush()


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", LISTEN), Handler)
    server.daemon_threads = True
    print(f"proxy :{LISTEN} -> {sys.argv[2]}, log {LOG}", flush=True)
    server.serve_forever()
