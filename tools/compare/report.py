#!/usr/bin/env python3
"""Prints the old-vs-new table from the two run.py result files, the APK sizes and the proxy log.

Usage: report.py <old.json> <new.json> <proxy.jsonl> <old.apk> <new.apk>
Writes the table to $GITHUB_STEP_SUMMARY when set, and always prints it plus a compact JSON between markers.
"""

import json
import os
import sys


def load(p):
    try:
        return json.load(open(p))
    except Exception as e:
        return {"error": str(e), "tasks": []}


def by_task(r):
    return {t["task"]: t for t in r.get("tasks", [])}


def mb(path):
    try:
        return f"{os.path.getsize(path) / 1024 / 1024:.1f} MB ({os.path.getsize(path) // 1024} KB)"
    except OSError:
        return "-"


def main():
    old, new = load(sys.argv[1]), load(sys.argv[2])
    to, tn = by_task(old), by_task(new)
    keys = [t["task"] for t in old.get("tasks", [])] or [t["task"] for t in new.get("tasks", [])]
    for k in [t["task"] for t in new.get("tasks", [])]:
        if k not in keys:
            keys.append(k)

    def cell(t, f):
        if not t:
            return "not run"
        v = t.get(f)
        return "-" if v is None else str(v)

    lines = ["| Measure | Old | New |", "|---|---|---|"]
    lines.append(f"| Version | {old.get('version', '-')} | {new.get('version', '-')} |")
    lines.append(f"| Set up through its own screens | {old.get('configured')} | {new.get('configured')} |")
    po = sum(1 for t in old.get("tasks", []) if t.get("passed"))
    pn = sum(1 for t in new.get("tasks", []) if t.get("passed"))
    lines.append(f"| Tasks completed correctly | {po}/{len(old.get('tasks', []))} | {pn}/{len(new.get('tasks', []))} |")
    hi_o, hi_n = to.get("hi", {}), tn.get("hi", {})
    lines.append(f"| Prompt tokens for \"hi\" (first request) | {cell(hi_o, 'first_prompt_tokens')} | {cell(hi_n, 'first_prompt_tokens')} |")
    lines.append(f"| Tools sent with \"hi\" | {cell(hi_o, 'tools_offered_first')} | {cell(hi_n, 'tools_offered_first')} |")
    for k in keys:
        a, b = to.get(k), tn.get(k)
        res = lambda t: "not run" if not t else ("pass" if t.get("passed") else "FAIL")
        lines.append(f"| Task {k}: result | {res(a)} | {res(b)} |")
        if k != "stop":
            lines.append(f"| Task {k}: prompt tokens (all requests) | {cell(a, 'total_prompt_tokens')} | {cell(b, 'total_prompt_tokens')} |")
            lines.append(f"| Task {k}: seconds | {cell(a, 'seconds')} | {cell(b, 'seconds')} |")
    co = sum(t.get("crashes", 0) or 0 for t in old.get("tasks", []))
    cn = sum(t.get("crashes", 0) or 0 for t in new.get("tasks", []))
    lines.append(f"| Crash-buffer lines during tasks | {co} | {cn} |")
    lines.append(f"| Release APK size | {mb(sys.argv[4])} | {mb(sys.argv[5])} |")
    io, inn = old.get("idle", {}), new.get("idle", {})
    for f, name in (("wakelock_ms", "Idle: wake lock ms"), ("wakelock_count", "Idle: wake locks taken"),
                    ("wakeup_alarms", "Idle: wakeup alarms"), ("cpu_ms", "Idle: CPU ms"), ("jobs", "Idle: jobs run")):
        lines.append(f"| {name} ({io.get('minutes', '?')} min, screen off) | {io.get(f, '-')} | {inn.get(f, '-')} |")
    table = "\n".join(lines)
    print("=====COMPARE TABLE=====")
    print(table)
    print("=====END=====")
    print("=====COMPARE DETAILS=====")
    for name, r in (("old", old), ("new", new)):
        for t in r.get("tasks", []):
            print(f"{name} {t.get('task')}: {'PASS' if t.get('passed') else 'FAIL'} | {t.get('why')} | calls {t.get('calls')} | "
                  f"{t.get('requests')} req | answer {str(t.get('answer', ''))[:200]!r} | errors {t.get('errors')} | crash {t.get('crash_head', [])[:3]}")
        print(f"{name} idle: {r.get('idle')}")
    print("=====END=====")
    print("=====PROXY LOG (compact)=====")
    try:
        for ln in open(sys.argv[3], encoding="utf-8"):
            e = json.loads(ln)
            u = (e.get("usage") or {}).get("prompt_tokens")
            t = e.get("timings") or {}
            print(e.get("label"), e.get("messages"), "msgs", e.get("tools"), "tools", "prompt", u, "processed", t.get("prompt_n"),
                  "cached", t.get("cache_n"), f"{e.get('t_end', 0) - e.get('t_start', 0):.0f}s",
                  [c["name"] for c in e.get("tool_calls", [])], repr((e.get("text") or "")[:80]), e.get("error") or "")
    except OSError as e:
        print(f"no proxy log: {e}")
    print("=====END=====")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("## Old vs new, same emulator and model\n\n" + table + "\n")
    sys.stdout.flush()


if __name__ == "__main__":
    main()
