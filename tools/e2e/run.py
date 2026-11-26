#!/usr/bin/env python3
"""End-to-end task tests: real agent runs on a CI emulator against a model served on the runner itself.

Runs only on GitHub's disposable runners (see .github/workflows/e2e.yml and docs/TESTING.md). No API key or secret is
involved: the app talks to a llama.cpp server on the runner, which the emulator reaches as 10.0.2.2.

The app is driven through its debug-only hook (app/src/debug, atom.app.e2e.E2eProvider):
  content call --uri content://<pkg>.e2e --method <configure|clear|stop|approve|deny|linux_install>
  content read --uri content://<pkg>.e2e/state        JSON: runs, tool calls, answers, scheduled tasks
Prompts go in through MainActivity's existing EXTRA_PROMPT, exactly like a voice command.

Scenarios assert on tool calls and device state, never on exact model wording. Every scenario ends with a screenshot
printed into the log (tools/screenshots/extract.py turns a log back into images) and a crash-buffer check.

Usage: python3 tools/e2e/run.py [scenario ...]     (default: all; E2E_ONLY="a,c" works too)
"""

import json
import os
import re
import shlex
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "screenshots"))
os.environ.setdefault("SHOT_DIR", "e2e-shots")

import capture  # noqa: E402  (shared adb, UI dump and screenshot helpers)
from capture import PKG, ACTIVITY, adb, sh, find, tap, tap_bounds, shot, launch, bubble_bounds, switch_checked  # noqa: E402

AUTHORITY = f"content://{PKG}.e2e"
SERVER = os.environ.get("E2E_SERVER", "http://127.0.0.1:8080")
# What the app uses: the emulator's alias for the runner's loopback.
APP_BASE_URL = os.environ.get("E2E_APP_BASE_URL", "http://10.0.2.2:8080/v1")
POLL_S = 3
# Set by setup() when the device clock can be fixed (adb root); toybox `date` format MMDDhhmmCCYY.ss.
FIXED_TIME = None


def log(msg):
    print(f"[e2e {time.strftime('%H:%M:%S')}] {msg}")
    sys.stdout.flush()


# ------------------------------------------------------------------ model server


def http_json(path, timeout=10):
    with urllib.request.urlopen(SERVER + path, timeout=timeout) as r:
        return json.loads(r.read().decode())


def server_info():
    """Model id (as /v1/models names it) and the real per-slot context window (from /props)."""
    models = http_json("/v1/models")
    model_id = models["data"][0]["id"]
    props = http_json("/props")
    n_ctx = props.get("default_generation_settings", {}).get("n_ctx") or props.get("n_ctx")
    return model_id, int(n_ctx), props


def server_metrics():
    """Cumulative prompt/generation token counts and seconds from llama-server --metrics (None if unavailable)."""
    try:
        with urllib.request.urlopen(SERVER + "/metrics", timeout=5) as r:
            text = r.read().decode()
    except Exception:
        return None
    out = {}
    for name in ("prompt_tokens_total", "prompt_seconds_total", "tokens_predicted_total", "tokens_predicted_seconds_total"):
        m = re.search(r"^llamacpp:" + name + r"\s+([0-9.eE+-]+)", text, re.M)
        out[name] = float(m.group(1)) if m else 0.0
    return out


def speed(before, after):
    if not before or not after:
        return ""
    pt = after["prompt_tokens_total"] - before["prompt_tokens_total"]
    ps = after["prompt_seconds_total"] - before["prompt_seconds_total"]
    gt = after["tokens_predicted_total"] - before["tokens_predicted_total"]
    gs = after["tokens_predicted_seconds_total"] - before["tokens_predicted_seconds_total"]
    parts = []
    if pt:
        parts.append(f"prefill {int(pt)} tok" + (f" @ {pt / ps:.0f} tok/s" if ps else ""))
    if gt:
        parts.append(f"gen {int(gt)} tok" + (f" @ {gt / gs:.1f} tok/s" if gs else ""))
    return ", ".join(parts)


# ------------------------------------------------------------------ app hook


def call(method, arg=None, **extras):
    cmd = f"content call --uri {AUTHORITY} --method {shlex.quote(method)}"
    if arg is not None:
        cmd += f" --arg {shlex.quote(str(arg))}"
    for k, v in extras.items():
        t = "b" if isinstance(v, bool) else "i" if isinstance(v, int) else "s"
        val = str(v).lower() if isinstance(v, bool) else str(v)
        cmd += f" --extra {shlex.quote(f'{k}:{t}:{val}')}"
    out = sh(cmd, timeout=90).strip()
    m = re.search(r"result=([^,}]*)", out)
    return m.group(1) if m else out


def state():
    for attempt in range(5):
        try:
            raw = adb("exec-out", "content", "read", "--uri", f"{AUTHORITY}/state", timeout=60)
            return json.loads(raw.decode(errors="replace"))
        except Exception as e:  # app restarting after a crash, binder busy...
            if attempt == 4:
                log(f"state unreadable: {e}")
            time.sleep(2)
    return {"runs": [], "scheduledTasks": [], "unreadable": True}


def run_ids():
    return {r["runId"] for r in state().get("runs", [])}


def send_prompt(prompt):
    out = sh(f"am start -n {ACTIVITY} -a android.intent.action.MAIN --es atom.app.EXTRA_PROMPT {shlex.quote(prompt)}")
    if "Error" in out:
        log(f"prompt intent failed: {out.strip()[:300]}")


def clear_crashes():
    adb("logcat", "-b", "crash", "-c")


def crash_log():
    """Crash-buffer entries of the app since the last clear (empty string when clean)."""
    text = adb("logcat", "-d", "-b", "crash", timeout=60).decode(errors="replace")
    lines = [ln for ln in text.splitlines() if ln.strip() and not ln.startswith("--------- beginning")]
    return "\n".join(lines) if any(PKG in ln or "FATAL EXCEPTION" in ln for ln in lines) else ""


def app_alive():
    return bool(sh(f"pidof {PKG}").strip())


def names(run):
    return [c["name"] for c in run.get("toolCalls", [])]


def answer(run):
    return (run.get("finalText") or "").strip()


class Watch:
    """Polls the app state for the newest run that was not there before, approving any confirmation on the way."""

    def __init__(self, known, source=None, approve=True):
        self.known = set(known)
        self.source = source
        self.approve = approve
        self.run = None
        self.seen_calls = 0
        self.mid_shot = False

    def poll(self):
        s = state()
        runs = [r for r in s.get("runs", []) if r["runId"] not in self.known and (self.source is None or r["source"] == self.source)]
        if not runs:
            return None
        run = max(runs, key=lambda r: r["startedAt"])
        self.run = run
        calls = run.get("toolCalls", [])
        for c in calls[self.seen_calls :]:
            res = (c.get("result") or "").replace("\n", " ")[:160]
            log(f"  tool {c['name']} {c.get('args', '')[:160]} -> {'ERROR ' if c.get('isError') else ''}{res}")
        self.seen_calls = len(calls)
        pending = run.get("pending")
        if pending and self.approve:
            log(f"  approving: {pending['action'][:200]}")
            call("approve", pending["id"])
        return run

    def wait(self, timeout, name, until=None):
        """Waits until the run ends (or [until] says so). Returns the last run state, or None if none started."""
        start = time.time()
        last_phase = None
        while time.time() - start < timeout:
            run = self.poll()
            if run:
                phase = run["phase"] + (f" ({run['currentTool']})" if run.get("currentTool") else "")
                if phase != last_phase:
                    log(f"  phase {phase}, tokens in/out {run.get('inputTokens')}/{run.get('outputTokens')}")
                    last_phase = phase
                if until and until(run):
                    return run
                if not run["active"]:
                    return run
            if not self.mid_shot and time.time() - start > 150:
                self.mid_shot = True
                shot(f"{name}_mid")
            time.sleep(POLL_S)
        log(f"  timed out after {timeout}s")
        if self.run and self.run["active"]:
            # Free the single model slot for the next scenario: an abandoned run would keep the server busy.
            log("  stopping the run: " + call("stop", self.run["conversationId"]))
            end = time.time() + 60
            while time.time() < end:
                run = self.poll()
                if run and not run["active"]:
                    break
                time.sleep(POLL_S)
            self.run["timedOut"] = True
        return self.run


# ------------------------------------------------------------------ scenarios


class Result:
    def __init__(self, key, title, core=True):
        self.key, self.title, self.core = key, title, core
        self.passed = False
        self.notes = []
        self.tools = []
        self.duration = 0.0
        self.speed = ""

    def check(self, ok, what):
        self.notes.append(("ok: " if ok else "FAIL: ") + what)
        return ok


def freeze_clock():
    """Sets the device clock to the same minute before every run. Atom's system prompt carries the time to the minute
    ahead of the tool list, so an identical minute lets llama-server reuse the cached prompt across scenarios instead
    of reading every tool schema again on the CPU."""
    if FIXED_TIME:
        sh(f"date {FIXED_TIME} >/dev/null")


def agent_task(res, prompt, timeout, source=None, background=False):
    known = run_ids()
    w = Watch(known, source=source)
    freeze_clock()
    send_prompt(prompt)
    if background:
        # The chat keeps running in the task service; off screen the emulator stops drawing and leaves the CPU
        # to the model server.
        time.sleep(5)
        sh("input keyevent KEYCODE_HOME")
    run = w.wait(timeout, res.key)
    if run is None:
        res.check(False, "no run started")
        return None
    res.tools = names(run)
    res.check(not run.get("timedOut") and not run["active"], f"run finished within {timeout}s ({run['phase']}, outcome {run.get('outcome')})")
    if run.get("error"):
        res.notes.append(f"error: {run['error'][:300]}")
    if run.get("notice"):
        res.notes.append(f"notice: {run['notice'][:200]}")
    log(f"  answer: {answer(run)[:600]!r}")
    if run.get("availableTools"):
        log(f"  tools offered to the model: {len(run['availableTools'])}")
    return run


def s_warm_up(res):
    """Not a test: the first request carries Atom's whole system prompt and every tool schema, which takes the CPU
    many minutes to read. Done once here; every scenario after it reuses the server's cached prompt."""
    run = agent_task(res, "Reply with just the word OK.", 2100, background=True)
    if run is not None:
        res.check(bool(answer(run)), "an answer came back")


def s_vibe(res):
    """a. Terminal: write and run a script, report its output."""
    st = state()
    deadline = time.time() + 120
    while "Ready" not in st.get("linux", "") and "Failed" not in st.get("linux", "") and time.time() < deadline:
        log(f"  waiting for Linux: {st.get('linux', '')[:160]}")
        time.sleep(15)
        st = state()
    res.notes.append(f"linux: {st.get('linux', '')[:120]}")
    run = agent_task(res, "In the terminal, create hello.py that prints the sum of 2 and 3, run it, and tell me the output.", 600)
    if run is None:
        return
    terminal = [n for n in names(run) if n.startswith("terminal_") or n in ("shell", "file_write")]
    res.check(bool(terminal), f"terminal/shell tools called: {terminal or 'none'}")
    res.check(re.search(r"\b5\b", answer(run)) is not None, "answer contains 5")


def s_browser(res):
    """b. Built-in agent browser (browser_open), not the external browser (open_url)."""
    run = agent_task(res, "Open the agent browser and summarize the top story on https://example.com.", 480)
    if run is None:
        return
    n = names(run)
    offered = run.get("availableTools") or []
    if offered:
        res.notes.append(f"browser tools offered: {sorted(t for t in offered if t.startswith('browser_')) or 'none'}")
    res.check("browser_open" in n, "browser_open called")
    res.check("open_url" not in n, "open_url not called")
    res.check(bool(answer(run)), "non-empty answer")


def zen_mode():
    return sh("settings get global zen_mode").strip()


def s_dnd(res):
    """c. Phone control: Do Not Disturb on."""
    sh("cmd notification set_dnd off")
    time.sleep(1)
    res.notes.append(f"zen_mode before: {zen_mode()}")
    run = agent_task(res, "Turn on Do Not Disturb.", 360)
    if run is None:
        return
    n = names(run)
    res.check(any(x in n for x in ("set_do_not_disturb", "set_toggle", "set_sound_mode")), "DND tool called")
    z = zen_mode()
    res.check(z not in ("0", "", "null"), f"zen_mode after: {z}")
    sh("cmd notification set_dnd off")


def s_notification(res):
    """d. Context: read the last notification."""
    # Posted as the shell user (adb may be root here, and a notification needs a package behind it).
    post = "cmd notification post -S bigtext -t 'Dinner' tag1 'Pizza at 8'"
    out = sh(f"su 2000 {post}" if FIXED_TIME else post)
    if out.strip():
        log(f"  notification post: {out.strip()[:200]}")
    time.sleep(3)
    run = agent_task(res, "What was my last notification about?", 360)
    if run is None:
        return
    n = names(run)
    res.check(any(x in n for x in ("notifications_current", "notification_history")), "notification tool called")
    res.check(re.search(r"pizza|dinner", answer(run), re.I) is not None, "answer mentions Pizza or Dinner")


def s_schedule(res):
    """e. Scheduling: a daily reminder becomes a scheduled task."""
    before = state().get("scheduledTasks", [])
    run = agent_task(res, "Every day at 9am remind me to drink water.", 360)
    if run is None:
        return
    res.check("scheduled_task_create" in names(run), "scheduled_task_create called")
    tasks = []
    for _ in range(5):  # the automation engine saves asynchronously
        tasks = state().get("scheduledTasks", [])
        if len(tasks) > len(before):
            break
        time.sleep(2)
    new = [t for t in tasks if t.get("id") not in {b.get("id") for b in before}]
    for t in new:
        res.notes.append(f"task: {t.get('name')!r} {str(t.get('schedule'))[:80]}")
    res.check(bool(new), f"scheduled task exists ({len(tasks)} total)")


def tap_notification_stop():
    """The running-task notification's Stop button, from the shade. True when it was found and tapped."""
    sh("cmd statusbar expand-notifications")
    time.sleep(2.5)
    ok = tap("^Stop$|^STOP$", wait=1.5)
    sh("cmd statusbar collapse")
    return ok


def s_stop(res):
    """f. Stop mid-task: after the first tool call, stop; the partial run must be kept."""
    known = run_ids()
    w = Watch(known)
    freeze_clock()
    send_prompt(
        "Check the device status, then list my installed apps, then read the screen, then check the time and location, "
        "and finally write a detailed report about everything you found."
    )
    run = w.wait(360, res.key, until=lambda r: bool(r.get("toolCalls")) or bool(r.get("currentTool")))
    if run is None:
        res.check(False, "no run started")
        return
    if not run["active"]:
        res.tools = names(run)
        res.check(False, f"run ended before the first tool call ({run['phase']})")
        return
    log("  first tool call seen; stopping from the notification")
    how = "notification Stop button" if tap_notification_stop() else None
    if how is None:
        log("  Stop button not found in the shade; sending the same broadcast through the debug hook")
        call("stop", run["conversationId"])
        how = "stop broadcast"
    res.notes.append(f"stopped via {how}")
    run = w.wait(90, res.key)
    res.tools = names(run)
    res.check(not run["active"], f"run ended ({run['phase']})")
    res.check(run["phase"] == "Cancelled" or run.get("outcome") == "Cancelled", "marked stopped (Cancelled)")
    time.sleep(2)
    saved = state()
    again = next((r for r in saved.get("runs", []) if r["runId"] == run["runId"]), run)
    count = again.get("savedMessages") or 0
    res.check(count >= 2, f"partial history saved ({count} messages, notices {again.get('savedNotices')})")
    res.check(app_alive(), "app process alive")


def s_character(res):
    """g. Character library: open Lumen."""
    launch("characters/library", wait=4)
    found = find("^Lumen$") or find("Lumen")
    if not res.check(found is not None, "Lumen listed"):
        return
    tap_bounds(found)
    time.sleep(5)
    res.check(app_alive(), "app process alive after opening Lumen")


def s_bubble(res):
    """h. Floating bubble: mini chat send, reply in the bubble's conversation."""
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    launch("overlay/settings", wait=3)
    on = switch_checked("Floating bubble")
    if on is False:
        tap("^Floating bubble$", wait=2)
        on = switch_checked("Floating bubble")
    res.notes.append(f"bubble switch: {on}")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    b = bubble_bounds()
    if not res.check(b is not None, "bubble window shown"):
        return
    tap_bounds(b)
    time.sleep(3)
    send = find("^Send$")
    mic = find("^Dictate$")
    if not res.check(send is not None or mic is not None, "mini chat open"):
        return
    anchor = mic or send
    sh(f"input tap {max(60, anchor[0] - 260)} {(anchor[1] + anchor[3]) // 2}")
    time.sleep(2)
    sh("input text 'What%sis%s2%splus%s2?'")
    time.sleep(2)
    shot(f"{res.key}_typed")
    if "mInputShown=true" in sh("dumpsys input_method | grep mInputShown"):
        # The on-screen keyboard can cover the send button; the first Back closes only the keyboard.
        sh("input keyevent KEYCODE_BACK")
        time.sleep(2)
    known = run_ids()
    w = Watch(known)
    send = find("^Send$")
    if not res.check(send is not None, "send button found"):
        return
    freeze_clock()
    tap_bounds(send)
    time.sleep(2)
    shot(f"{res.key}_sent")
    run = w.wait(360, res.key)
    if not res.check(run is not None, "bubble run started"):
        return
    res.check(run["source"] == "Bubble", f"run came from the bubble (source {run['source']})")
    res.tools = names(run)
    res.check(not run["active"], f"run ended ({run['phase']})")
    log(f"  bubble answer: {answer(run)[:300]!r}")
    res.check(bool(answer(run)), "reply in the bubble's conversation")
    shot(f"{res.key}_panel")
    tap("^Close$", wait=1.5)


SCENARIOS = [
    # Ordered so the Linux install (queued at setup) has time to finish before the terminal task.
    ("g", "Character: open Lumen", s_character),
    ("0", "Warm-up: prompt cache (not core)", s_warm_up),
    ("c", "Phone control: Do Not Disturb", s_dnd),
    ("d", "Context: last notification", s_notification),
    ("e", "Scheduling: daily reminder", s_schedule),
    ("b", "Agent browser: example.com", s_browser),
    ("a", "Vibe coding: hello.py in the terminal", s_vibe),
    ("f", "Stop mid-task", s_stop),
    ("h", "Bubble mini chat", s_bubble),
]


# ------------------------------------------------------------------ setup


def setup():
    capture.require_installed()
    model_id, n_ctx, _ = server_info()
    log(f"model server: id={model_id} n_ctx={n_ctx}")
    for cmd in (
        f"pm grant {PKG} android.permission.POST_NOTIFICATIONS",
        f"appops set {PKG} SYSTEM_ALERT_WINDOW ignore",  # the bubble stays off the chat scenarios until h
        f"cmd notification allow_dnd {PKG}",
        f"cmd notification allow_listener {PKG}/atom.context.notifications.AtomNotificationListener",
        f"settings put secure enabled_accessibility_services {PKG}/atom.device.AtomAccessibilityService",
        "settings put secure accessibility_enabled 1",
        "settings put global window_animation_scale 0.5",
        "settings put system screen_off_timeout 1800000",
        "svc power stayon true",
    ):
        out = sh(cmd).strip()
        log(f"$ {cmd}" + (f" -> {out[:200]}" if out else ""))
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    # A fixed clock (see freeze_clock) needs root; google_apis emulator images allow adb root.
    global FIXED_TIME
    out = subprocess.run(["adb", "root"], capture_output=True, timeout=60).stdout.decode(errors="replace").strip()
    subprocess.run(["adb", "wait-for-device"], timeout=120)
    time.sleep(3)
    if sh("id -u").strip() == "0":
        sh("settings put global auto_time 0")
        FIXED_TIME = time.strftime("%m%d0900%Y.00")
        freeze_clock()
        log(f"adb root: {out}; device clock fixed at 09:00 for every run: {sh('date').strip()}")
    else:
        log(f"adb root unavailable ({out}); the clock runs, so each scenario's first request reads the whole prompt")
    launch(wait=6)
    options = {
        "base_url": APP_BASE_URL,
        "model": model_id,
        "context_window": n_ctx,
        "titles": False,
        # Streams prompt-processing progress so a long CPU prefill never hits the app's read timeout.
        "extra_body": {"return_progress": True},
    }
    configured = call("configure", json.dumps(options, separators=(",", ":")))
    log("configure: " + configured)
    if not configured.startswith("configured"):
        capture.fail("the debug configure call failed")
    # The settings are flows: the open chat picks up the selection and the finished onboarding without a restart.
    launch(wait=4)
    # The terminal task needs Linux, as a user would have installed it; it downloads while other scenarios run.
    log("linux: " + call("linux_install", "Alpine"))
    st = state()
    log(f"selection={st.get('selection')} onboarded={st.get('onboarded')} accessibility={st.get('accessibility')}")
    if not st.get("onboarded") or "local-test-model" not in str(st.get("selection")):
        capture.fail("the debug configure call did not take effect")
    shot("e2e_00_home")


def main():
    only = set(a.lower() for a in sys.argv[1:]) or set(filter(None, os.environ.get("E2E_ONLY", "").lower().split(",")))
    setup()
    results = []
    for key, title, fn in SCENARIOS:
        if only and key not in only and key != "0":
            continue
        res = Result(key, title, core=key != "0")
        log(f"===== {key}. {title} =====")
        call("clear")
        clear_crashes()
        m0 = server_metrics()
        t0 = time.time()
        try:
            fn(res)
        except (Exception, SystemExit) as e:  # a harness problem in one scenario must not hide the others
            res.check(False, f"harness error: {type(e).__name__}: {e}")
        res.duration = time.time() - t0
        res.speed = speed(m0, server_metrics())
        crash = crash_log()
        if res.check(not crash, "no crash in logcat -b crash") is False:
            print("=====CRASH " + key + "=====")
            # The head carries the exception and the app's own frames; Compose's layout frames follow.
            print("\n".join(crash.splitlines()[:80]))
            print("=====END=====")
        res.passed = all(n.startswith("ok: ") for n in res.notes if n.startswith(("ok: ", "FAIL: ")))
        try:
            shot(f"e2e_{key}_end")
        except Exception as e:
            log(f"screenshot failed: {e}")
        log(f"{key}: {'PASS' if res.passed else 'FAIL'} in {res.duration:.0f}s; {res.speed}")
        for n in res.notes:
            log("  " + n)
        results.append(res)
        # Leave the next scenario a clean home chat.
        sh("input keyevent KEYCODE_HOME")
        time.sleep(1)
    scorecard(results)
    failed = [r for r in results if r.core and not r.passed]
    sys.exit(1 if failed else 0)


def scorecard(results):
    rows = [("", "Scenario", "Result", "Tool calls", "Time", "Model speed")]
    for r in results:
        tools = ", ".join(r.tools) if r.tools else "-"
        rows.append((r.key, r.title, "PASS" if r.passed else "FAIL", tools[:70], f"{r.duration:.0f}s", r.speed or "-"))
    widths = [max(len(row[i]) for row in rows) for i in range(len(rows[0]))]
    line = "+".join("-" * (w + 2) for w in widths)
    print("=====SCORECARD=====")
    for i, row in enumerate(rows):
        print(" | ".join(c.ljust(w) for c, w in zip(row, widths)))
        if i == 0:
            print(line)
    print()
    for r in results:
        if not r.passed:
            print(f"{r.key}. {r.title}:")
            for n in r.notes:
                print("    " + n)
    print("=====END=====")
    passed = sum(r.passed for r in results)
    print(f"{passed}/{len(results)} scenarios passed")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("## End-to-end tasks\n\n| | Scenario | Result | Tool calls | Time | Model speed |\n|---|---|---|---|---|---|\n")
            for r in results:
                f.write(f"| {r.key} | {r.title} | {'PASS' if r.passed else 'FAIL'} | {', '.join(r.tools) or '-'} | {r.duration:.0f}s | {r.speed or '-'} |\n")
            f.write(f"\n{passed}/{len(results)} passed\n")
            for r in results:
                if not r.passed:
                    f.write(f"\n**{r.key}. {r.title}**\n\n" + "".join(f"- {n}\n" for n in r.notes))
    sys.stdout.flush()


if __name__ == "__main__":
    main()
