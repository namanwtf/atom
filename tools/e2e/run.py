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
# Each poll makes the app serialize its runs; on an emulator that translates the app from ARM, keep it light.
POLL_S = 5
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
    check_device()
    return {"runs": [], "scheduledTasks": [], "unreadable": True}


class DeviceGone(Exception):
    """The emulator itself stopped answering: nothing after this can be measured."""


def check_device():
    try:
        r = subprocess.run(["adb", "get-state"], capture_output=True, timeout=20)
        if r.stdout.decode(errors="replace").strip() == "device":
            return
        detail = (r.stdout + r.stderr).decode(errors="replace").strip()
    except subprocess.TimeoutExpired:
        detail = "adb get-state timed out"
    raise DeviceGone(f"the emulator stopped answering ({detail})")


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
        # Each call is logged once its result is in (or the run ended without one).
        while self.seen_calls < len(calls):
            c = calls[self.seen_calls]
            if c.get("result") is None and run["active"]:
                break
            res = (c.get("result") or "(no result)").replace("\n", " ")[:200]
            log(f"  tool {c['name']} {c.get('args', '')[:160]} -> {'ERROR ' if c.get('isError') else ''}{res}")
            self.seen_calls += 1
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
        if self.run and self.run["active"] and until is None:
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


A11Y = f"{PKG}/atom.device.AtomAccessibilityService"


def ensure_accessibility(wait_s=30):
    """Atom's accessibility service, as a user enables it in Settings. It does not always connect on the first
    try on a fresh emulator, so it is switched off and on again until the app reports it connected."""
    for attempt in range(3):
        end = time.time() + wait_s
        while time.time() < end:
            if state().get("accessibility"):
                return True
            time.sleep(3)
        log(f"accessibility service not connected; enabling it again (attempt {attempt + 1})")
        sh("settings put secure enabled_accessibility_services ''")
        time.sleep(2)
        sh(f"settings put secure enabled_accessibility_services {A11Y}")
        sh("settings put secure accessibility_enabled 1")
    log("accessibility service still not connected; screen tools will fail")
    return False


def freeze_clock():
    """Sets the device clock to the same minute (09:00 today) before every run, so each task sees the same time:
    the user turn that carries the time is then the same from run to run, and the server's prompt cache matches
    across re-runs of a scenario."""
    if FIXED_TIME:
        sh(f"date {FIXED_TIME} >/dev/null")


def post_notification(args):
    """`cmd notification post` as the shell user: adb may be root here, and a notification needs a package behind it."""
    post = f"cmd notification post {args}"
    out = sh(f"su 2000 {post}" if FIXED_TIME else post)
    if out.strip():
        log(f"  notification post: {out.strip()[:200]}")


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
    res.check(
        not run.get("timedOut") and not run["active"],
        f"run finished within {timeout}s ({run['phase']}, outcome {run.get('outcome')})",
    )
    if run.get("error"):
        res.notes.append(f"error: {run['error'][:300]}")
    if run.get("notice"):
        res.notes.append(f"notice: {run['notice'][:200]}")
    log(f"  answer: {answer(run)[:600]!r}")
    if run.get("availableTools"):
        log(f"  tools offered to the model: {len(run['availableTools'])}")
    return run


def s_warm_up(res):
    """Not a test: the first request carries Atom's whole system prompt and every core tool schema, which takes the
    runner's CPU minutes to read. Done once here, with the app in the background; every scenario after it reuses
    the server's cached prompt."""
    run = agent_task(res, "Reply with just the word OK.", 1500, background=True)
    if run is not None:
        res.check(bool(answer(run)), "an answer came back")


def s_vibe(res):
    """a. Terminal: write and run a script, report its output."""
    st = state()
    deadline = time.time() + 240
    while "Ready" not in st.get("linux", "") and "Failed" not in st.get("linux", "") and time.time() < deadline:
        log(f"  waiting for Linux: {st.get('linux', '')[:160]}")
        time.sleep(15)
        st = state()
    res.notes.append(f"linux: {st.get('linux', '')[:120]}")
    run = agent_task(res, "In the terminal, create hello.py that prints the sum of 2 and 3, run it, and tell me the output.", 900)
    if run is None:
        return
    terminal = [n for n in names(run) if n.startswith("terminal_") or n in ("shell", "file_write")]
    res.check(bool(terminal), f"terminal/shell tools called: {terminal or 'none'}")
    res.check(re.search(r"\b5\b", answer(run)) is not None, "answer contains 5")
    # The answer alone could be the model's arithmetic; the script must really have printed it.
    ran = [c for c in run.get("toolCalls", []) if c["name"] in terminal and re.search(r"(?m)^\s*5\s*$", c.get("result") or "")]
    res.check(bool(ran), "a terminal result shows the script printing 5")


def s_browser(res):
    """b. Built-in agent browser (browser_open), not the external browser (open_url)."""
    run = agent_task(res, "Open the agent browser and summarize the top story on https://example.com.", 600)
    if run is None:
        return
    n = names(run)
    offered = run.get("availableTools") or []
    if offered:
        res.notes.append(f"browser tools offered: {sorted(t for t in offered if t.startswith('browser_')) or 'none'}")
    res.check("browser_open" in n, "browser_open called")
    opened = [c for c in run.get("toolCalls", []) if c["name"] == "browser_open"]
    if opened:
        res.check(any(not c.get("isError") for c in opened), "browser_open loaded the page (no error result)")
    res.check("open_url" not in n, "open_url not called")
    res.check(bool(answer(run)), "non-empty answer")


def s_browser_read(res):
    """o. Agent browser end to end: the page loads in Atom's own browser, its text is read, and the model summarizes it.

    Guards the 4.0.4 failure where every browser_open died with "WebView method was called on thread ...".
    """
    run = agent_task(
        res,
        "Use the agent browser to open https://example.com, read the page, and tell me in one sentence what it says.",
        600,
    )
    if run is None:
        return
    calls = run.get("toolCalls", [])
    browser = [c for c in calls if c["name"].startswith("browser_")]
    res.check(bool(browser), f"browser tools called: {[c['name'] for c in browser] or 'none'}")
    threading = [c for c in browser if "thread" in (c.get("result") or "").lower() and c.get("isError")]
    res.check(not threading, "no WebView threading errors" + (f": {threading[0].get('result', '')[:200]}" if threading else ""))
    opened = [c for c in browser if c["name"] == "browser_open" and not c.get("isError")]
    res.check(bool(opened), "browser_open succeeded")
    read = [c for c in browser if not c.get("isError") and "example domain" in (c.get("result") or "").lower()]
    res.check(bool(read), "the page text (Example Domain) reached the model")
    res.check(re.search(r"example|illustrative|domain", answer(run), re.I) is not None, "the answer summarizes the page")


def s_site(res):
    """p (optional). Make a website: a file is written through the site tools and its live preview loads."""
    run = agent_task(res, "Make a one-page site that says hello.", 900)
    if run is None:
        return
    calls = run.get("toolCalls", [])
    site_calls = [c["name"] for c in calls if c["name"].startswith("site_")]
    res.check(bool(site_calls), f"site tools called: {site_calls or 'none'}")
    wrote = [c for c in calls if c["name"] == "site_write" and not c.get("isError")]
    created = [c for c in calls if c["name"] == "site_create" and not c.get("isError")]
    res.check(bool(wrote or created), "a site file was written")
    # The files themselves, read from the app's private storage (debug builds allow run-as).
    listing = sh(f"run-as {PKG} sh -c 'ls -R files/sites 2>/dev/null'").strip()
    log(f"  files/sites: {listing[:400]!r}")
    pages = sh(f"run-as {PKG} sh -c 'cat files/sites/*/index.html 2>/dev/null'")
    res.check("index.html" in listing, "files/sites/<site>/index.html exists")
    res.check(re.search(r"hello", pages, re.I) is not None, "a page says hello")
    previews = [c for c in calls if c["name"] == "site_preview" and not c.get("isError")]
    res.check(bool(previews), "site_preview succeeded")
    url = None
    for c in previews:
        m = re.search(r"URL: http://127\.0\.0\.1:(\d+)/", c.get("result") or "")
        if m:
            url = m
    if url is None:
        res.check(False, "the preview URL was returned")
        return
    port = url.group(1)
    adb("forward", "tcp:18555", f"tcp:{port}")
    try:
        with urllib.request.urlopen("http://127.0.0.1:18555/", timeout=15) as r:
            body = r.read().decode(errors="replace")
            res.check(r.status == 200 and re.search(r"hello", body, re.I) is not None, "the preview server serves the page")
            res.check("__atom_reload" in body, "the page carries the live reload script")
    except Exception as e:
        res.check(False, f"the preview server answered ({e})")
    finally:
        adb("forward", "--remove", "tcp:18555")
    shot("e2e_p_preview")


def zen_mode():
    return sh("settings get global zen_mode").strip()


def s_dnd(res):
    """c. Phone control: Do Not Disturb on."""
    sh("cmd notification set_dnd off")
    time.sleep(1)
    res.notes.append(f"zen_mode before: {zen_mode()}")
    run = agent_task(res, "Turn on Do Not Disturb.", 480)
    if run is None:
        return
    n = names(run)
    res.check(any(x in n for x in ("set_do_not_disturb", "set_toggle", "set_sound_mode")), "DND tool called")
    z = zen_mode()
    res.check(z not in ("0", "", "null"), f"zen_mode after: {z}")
    sh("cmd notification set_dnd off")


def s_notification(res):
    """d. Context: read the last notification."""
    post_notification("-S bigtext -t 'Dinner' tag1 'Pizza at 8'")
    time.sleep(3)
    run = agent_task(res, "What was my last notification about?", 480)
    if run is None:
        return
    n = names(run)
    res.check(any(x in n for x in ("notifications_current", "notification_history")), "notification tool called")
    res.check(re.search(r"pizza|dinner", answer(run), re.I) is not None, "answer mentions Pizza or Dinner")


def s_schedule(res):
    """e. Scheduling: a daily reminder becomes a scheduled task."""
    before = state().get("scheduledTasks", [])
    run = agent_task(res, "Every day at 9am remind me to drink water.", 480)
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
    if not ok:
        # A collapsed notification hides its buttons: tap its Expand (the one nearest the task's text), look again.
        nodes = capture.dump()
        task = next((b for t, d, b in nodes if b and re.match(r"Check the device status", t or "")), None)
        expands = [b for t, d, b in nodes if b and (t == "Expand" or d == "Expand")]
        if task and expands:
            cy = (task[1] + task[3]) // 2
            tap_bounds(min(expands, key=lambda b: abs((b[1] + b[3]) // 2 - cy)))
            time.sleep(1.5)
            ok = tap("^Stop$|^STOP$", wait=1.5)
        if not ok:
            shot("f_shade")
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
    run = w.wait(480, res.key, until=lambda r: bool(r.get("toolCalls")) or bool(r.get("currentTool")))
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
    found = None
    for _ in range(8):  # the library loads its cards after the screen appears
        found = find("^Lumen$") or find(r"^Lumen\b")
        if found:
            break
        time.sleep(2)
    if not res.check(found is not None, "Lumen listed"):
        shot(f"{res.key}_library")
        return
    tap_bounds(found)
    time.sleep(5)
    res.check(app_alive(), "app process alive after opening Lumen")
    shot(f"{res.key}_lumen")
    # Back to the chat, so the next tasks do not start on this screen.
    capture.back()
    capture.back()


def keyboard_shown():
    return "mInputShown=true" in sh("dumpsys input_method | grep mInputShown")


def ui_nodes():
    """The current UI hierarchy as (class, text, desc, bounds) tuples (capture.dump without the class)."""
    import xml.etree.ElementTree as ET

    for _ in range(3):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        try:
            root = ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))
            break
        except ET.ParseError:
            time.sleep(1)
    else:
        return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        out.append((n.get("class", ""), n.get("text", ""), n.get("content-desc", ""), tuple(int(x) for x in b) if len(b) == 4 else None))
    return out


def composer_and_send():
    """The mini chat's composer field while it holds the typed text (an EditText; the sent message in the list is
    plain text) and its Send button, from a fresh dump."""
    nodes = ui_nodes()
    field = next((b for c, t, d, b in nodes if b and "EditText" in c and re.search(r"2 plus 2", t or "")), None)
    send = next((b for c, t, d, b in nodes if b and (d == "Send" or t == "Send")), None)
    return field, send


def bubble_send(res):
    """Taps the mini chat's Send. The overlay moves up for the keyboard, and the accessibility bounds of the Send
    button can still be the ones from before the move: a tap there lands on the keyboard's Enter key (scenario h
    never passed for this). The composer field reports fresh bounds because its text just changed, and Send sits on
    the same row (both are bottom-aligned 46 dp), so the tap goes to Send's x on the field's row. If the message is
    still in the composer after that, the keyboard is closed (the first Back only closes the keyboard) and Send is
    found again. Every tap logs the bounds it used."""
    for attempt in ("fresh composer bounds", "keyboard closed"):
        if attempt == "keyboard closed":
            if not keyboard_shown():
                break
            sh("input keyevent KEYCODE_BACK")
            time.sleep(2)
        field, send = composer_and_send()
        ime = keyboard_shown()
        if field is None or send is None:
            log(f"  {attempt}: composer with the typed text {field}, Send {send}, keyboard shown {ime}; cannot send")
            res.notes.append(f"{attempt}: composer {field}, Send {send}")
            return False
        x = (send[0] + send[2]) // 2
        y = (field[1] + field[3]) // 2
        log(f"  tapping Send at ({x},{y}) [{attempt}]: Send node {send}, composer node {field}, keyboard shown {ime}")
        res.notes.append(f"Send tap ({attempt}) at ({x},{y}); Send {send}, composer {field}")
        sh(f"input tap {x} {y}")
        time.sleep(2.5)
        field_after, _ = composer_and_send()
        if field_after is None:
            return True
        log(f"  the message is still in the composer after the tap ({field_after})")
    return False


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
    known = run_ids()
    w = Watch(known)
    freeze_clock()
    sent = bubble_send(res)
    shot(f"{res.key}_sent")
    if not res.check(sent, "Send tapped and the message left the composer"):
        return
    run = w.wait(480, res.key)
    if not res.check(run is not None, "bubble run started"):
        return
    res.check(run["source"] == "Bubble", f"run came from the bubble (source {run['source']})")
    res.tools = names(run)
    res.check(not run["active"], f"run ended ({run['phase']})")
    log(f"  bubble answer: {answer(run)[:300]!r}")
    res.check(bool(answer(run)), "reply in the bubble's conversation")
    shot(f"{res.key}_panel")
    tap("^Close$", wait=1.5)


def s_receiver(res):
    """i. Battery fix check: the boot / clock receiver is on while an automation exists (it was off with none)."""
    before = SETUP.get("receiver_before")
    res.notes.append(f"receiver before any automation: {before}")
    st = state()
    tasks = [t for t in st.get("scheduledTasks", []) if t.get("enabled")]
    now = st.get("systemEventReceiver")
    res.notes.append(f"enabled scheduled tasks: {len(tasks)}; receiver now: {now}")
    if not tasks:
        # The test model did not create one in e: create it the way the agent's tool does, so this check of the
        # receiver does not depend on the model.
        out = call("run_tool", json.dumps({"name": "scheduled_task_create", "args": {"name": "E2E water", "prompt": "Remind me to drink water", "schedule": "daily 09:00"}}))
        res.notes.append(f"created a task with the tool directly: {out[:160]}")
        time.sleep(3)
        st = state()
        tasks = [t for t in st.get("scheduledTasks", []) if t.get("enabled")]
        now = st.get("systemEventReceiver")
    if not res.check(bool(tasks), "an enabled scheduled task exists"):
        return
    res.check(now in ("enabled", "default"), f"receiver enabled while a task exists ({now})")


def s_notification_history(res):
    """j. Battery fix check: skipping progress updates must not lose ordinary notifications in the history."""
    post_notification("-t 'Parcel' tag2 'Your parcel arrives today'")
    found = None
    for _ in range(10):
        time.sleep(2)
        found = next((n for n in state().get("notificationHistory", []) if n.get("title") == "Parcel"), None)
        if found:
            break
    res.notes.append(f"history head: {[n.get('title') for n in state().get('notificationHistory', [])][:5]}")
    res.check(found is not None, "ordinary notification recorded in the history")


def wake_state():
    return state().get("wake", "")


def wait_wake(pattern, timeout=20):
    end = time.time() + timeout
    w = wake_state()
    while time.time() < end:
        w = wake_state()
        if re.search(pattern, w):
            return True, w
        time.sleep(2)
    return False, w


def s_hey_atom_charging(res):
    """k. Battery fix check: "Hey Atom" pauses with the screen off on battery and listens again when plugged in."""
    st = state()
    res.notes.append(f"pack ready: {st.get('packReady')}")
    if not res.check(bool(st.get("packReady")), "on-device AI pack installed (it carries the wake word)"):
        return
    sh(f"pm grant {PKG} android.permission.RECORD_AUDIO")
    launch(wait=3)  # the microphone service may only start from the foreground
    log("  " + call("hey_atom", "on"))
    ok, w = wait_wake(r"^Listening")
    res.check(ok, f"screen on: listening ({w})")
    try:
        sh("dumpsys battery unplug")
        sh("input keyevent KEYCODE_SLEEP")
        ok, w = wait_wake(r"Paused.*[Ss]creen")
        res.check(ok, f"screen off, on battery: paused ({w})")
        sh("dumpsys battery set ac 1")
        ok, w = wait_wake(r"^Listening")
        res.check(ok, f"screen off, plugged in: listening ({w})")
        sh("dumpsys battery set ac 0")
        ok, w = wait_wake(r"Paused")
        res.check(ok, f"screen off, unplugged again: paused ({w})")
    finally:
        sh("dumpsys battery reset")
        sh("input keyevent KEYCODE_WAKEUP")
        sh("wm dismiss-keyguard")
        log("  " + call("hey_atom", "off"))


def screen_off():
    sh("svc power stayon false")
    sh("input keyevent KEYCODE_SLEEP")
    time.sleep(2)


def screen_on():
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    sh("wm dismiss-keyguard")
    sh("input keyevent 82")  # menu dismisses a swipe-only keyguard
    time.sleep(2)
    sh("svc power stayon true")


def task_wake_lock():
    """True while Atom's task wake lock is currently held (only the live list, not the wake lock history)."""
    out = sh("dumpsys power")
    m = re.search(r"Wake Locks: size=\d+\n((?:\s+.*\n)*?)\s*\n", out)
    return bool(m) and "atom:task" in m.group(1)


def completed(run):
    return run.get("outcome") == "Completed" or run.get("phase") in ("Done", "Completed")


def service_foreground():
    out = sh(f"dumpsys activity services {PKG} | grep -iE 'AgentService|isForeground'")
    return "AgentService" in out and "isForeground=true" in out


def first_activity(w, name, timeout=480):
    return w.wait(timeout, name, until=lambda r: bool(r.get("toolCalls")) or bool(r.get("currentTool")))


def s_screen_off_background(res):
    """m. Screen off mid-task, no screen needed: the run keeps going (foreground service, wake lock) and finishes."""
    known = run_ids()
    w = Watch(known)
    freeze_clock()
    send_prompt("Run the shell command 'sleep 20; echo screen-off-ok' and tell me exactly what it printed.")
    run = first_activity(w, res.key)
    if not res.check(run is not None and run["active"], "run started and is working"):
        return
    screen_off()
    time.sleep(6)
    held, fg = task_wake_lock(), service_foreground()
    res.notes.append(f"screen off: wake lock atom:task held={held}, AgentService foreground={fg}")
    run = w.wait(600, res.key)
    off_end = not "Awake" in sh("dumpsys power | grep -E 'mWakefulness='")
    res.notes.append(f"screen still off when the run ended: {off_end}")
    time.sleep(3)
    released = not task_wake_lock()
    screen_on()
    res.tools = names(run)
    res.check(completed(run), f"run completed with the screen off ({run['phase']}, {run.get('outcome')})")
    res.check("screen-off-ok" in answer(run), "answer has the command's output")
    res.check(held, "partial wake lock held while the screen was off")
    res.check(fg, "foreground service running during the task")
    res.check(released, "wake lock released after the run")


def notification_texts():
    out = sh("dumpsys notification --noredact")
    return " ".join(re.findall(r"android\.(?:text|title)=(?:String|SpannableString) \(([^)]*)\)", out))


def s_screen_off_ui(res):
    """n. Screen off mid-task while operating apps: the run waits for an unlocked screen instead of failing or
    spinning, says so in its notification, and carries on after unlocking."""
    known = run_ids()
    w = Watch(known)
    freeze_clock()
    send_prompt("Open the Settings app, read its first screen and tell me three of the items listed there.")
    run = first_activity(w, res.key)
    if not res.check(run is not None and run["active"], "run started and is working"):
        return
    calls_before = len(run.get("toolCalls", []))
    screen_off()
    waiting, note, lock_while_waiting = False, "", False
    for _ in range(12):  # about a minute with the screen off
        time.sleep(5)
        st = state()
        if st.get("screenWait"):
            waiting = True
            lock_while_waiting = lock_while_waiting or task_wake_lock()
        texts = notification_texts()
        if "unlocked" in texts:
            note = "Waiting for the screen to be unlocked"
    run = w.poll() or run
    calls_off = len(run.get("toolCalls", [])) - calls_before
    phase_off = run["phase"]
    res.notes.append(f"while off: phase {phase_off}, {calls_off} new tool calls, waiting={waiting}, notification: {note or '-'}")
    screen_on()
    launch(wait=2)
    run = w.wait(600, res.key)
    res.tools = names(run)
    res.check(phase_off != "Failed", "did not fail while the screen was off")
    res.check(calls_off <= 2, f"did not spin while the screen was off ({calls_off} calls)")
    res.check(waiting, "waited for the screen to be unlocked")
    res.check(bool(note), "notification said it is waiting for the screen to be unlocked")
    res.check(not lock_while_waiting, "no task wake lock while it waited (the wait is woken by the unlock)")
    res.check(completed(run), f"carried on and completed after unlocking ({run['phase']})")
    res.check(bool(answer(run)), "an answer")


def boot_receiver_enabled():
    """True when the system would deliver BOOT_COMPLETED to Atom's receiver (only enabled receivers are listed)."""
    out = sh("cmd package query-receivers --components -a android.intent.action.BOOT_COMPLETED")
    return f"{PKG}/atom.automation.system.SystemEventReceiver" in out


def s_reboot(res):
    """l. Battery fix check: after a reboot the receiver is still on, Atom starts for BOOT_COMPLETED and re-arms."""
    tasks = [t for t in state().get("scheduledTasks", []) if t.get("enabled")]
    if not res.check(bool(tasks), f"an enabled scheduled task exists before the reboot ({len(tasks)})"):
        return
    res.notes.append(f"before reboot: boot receiver enabled={boot_receiver_enabled()}")
    adb("reboot", timeout=60)
    time.sleep(10)
    adb("wait-for-device", timeout=300)
    end = time.time() + 300
    while time.time() < end and sh("getprop sys.boot_completed").strip() != "1":
        time.sleep(5)
    time.sleep(30)  # BOOT_COMPLETED is delivered after boot_completed is set
    res.check(boot_receiver_enabled(), "receiver still enabled after the reboot")
    alive = app_alive()
    res.notes.append(f"Atom process after boot (started by BOOT_COMPLETED): {alive}")
    alarms = sh("dumpsys alarm")
    res.check(PKG in alarms, "Atom's alarm is armed again after the reboot")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")


SETUP = {}

SCENARIOS = [
    # Ordered so the Linux install (queued at setup) has time to finish before the terminal task.
    ("g", "Character: open Lumen", s_character),
    # Not scored: reads Atom's first request once, so the scenarios after it reuse the server's prompt cache.
    ("0", "Warm-up: prompt cache (not scored)", s_warm_up, False),
    ("c", "Phone control: Do Not Disturb", s_dnd),
    ("d", "Context: last notification", s_notification),
    ("e", "Scheduling: daily reminder", s_schedule),
    ("b", "Agent browser: example.com", s_browser),
    ("o", "Agent browser: open, read and summarize", s_browser_read),
    ("a", "Vibe coding: hello.py in the terminal", s_vibe),
    ("f", "Stop mid-task", s_stop),
    ("h", "Bubble mini chat", s_bubble),
    # Checks that the battery fixes (#22) did not switch features off. i needs the task from e; l reboots, so it
    # comes last.
    ("i", "Automations: boot receiver on while a task exists", s_receiver),
    ("j", "Notification history keeps ordinary notifications", s_notification_history),
    ("k", "Hey Atom: pauses on battery, listens when charging", s_hey_atom_charging),
    # The owner's question: does a run survive the screen turning off mid-task?
    ("m", "Screen off mid-task: a shell task finishes", s_screen_off_background),
    ("n", "Screen off mid-task: a screen task waits for unlock, then finishes", s_screen_off_ui),
    # Optional: a small model may not manage a whole site; a failure is reported but does not fail the job.
    ("p", "Make a website: one page that says hello", s_site, False),
    ("l", "Reboot: receiver, boot start and alarm survive", s_reboot),
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
        # No lock screen: an agent may lock the phone (global_action lock_screen), and the next task needs the screen.
        "locksettings set-disabled true",
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
        log(f"adb root unavailable ({out}); the clock runs as usual")
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
    ensure_accessibility()
    st = state()
    SETUP["receiver_before"] = st.get("systemEventReceiver")
    log(f"selection={st.get('selection')} onboarded={st.get('onboarded')} accessibility={st.get('accessibility')} "
        f"receiver={st.get('systemEventReceiver')} wake={st.get('wake')} pack={st.get('packReady')}")
    if not st.get("onboarded") or "local-test-model" not in str(st.get("selection")):
        capture.fail("the debug configure call did not take effect")
    shot("e2e_00_home")


def main():
    only = set(a.lower() for a in sys.argv[1:]) or set(filter(None, os.environ.get("E2E_ONLY", "").lower().split(",")))
    setup()
    results = []
    gone = None
    for key, title, fn, *flags in SCENARIOS:
        if only and key not in only and key != "0":
            continue
        res = Result(key, title, core=flags[0] if flags else True)
        results.append(res)
        if gone:
            res.check(False, "not run: " + gone)
            continue
        try:
            run_one(res, fn)
        except DeviceGone as e:
            gone = str(e)
            res.check(False, gone)
            res.passed = False
            log(f"{key}: FAIL; {gone}. The remaining scenarios cannot run.")
    scorecard(results)
    failed = [r for r in results if r.core and not r.passed]
    sys.exit(1 if failed else 0)


def run_one(res, fn):
    key = res.key
    log(f"===== {key}. {res.title} =====")
    # A run the previous scenario left going (it timed out) would hold phone control and make this one wait.
    leftover = [r for r in state().get("runs", []) if r.get("active")]
    if leftover:
        log(f"  stopping {len(leftover)} run(s) left from the previous scenario")
        call("stop")
        for _ in range(20):
            time.sleep(3)
            if not any(r.get("active") for r in state().get("runs", [])):
                break
    call("clear")
    # Whatever the last task did to the screen (locked it, left a panel or the shade open), start awake and home.
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    sh("cmd statusbar collapse")
    sh("input keyevent KEYCODE_HOME")
    if not ensure_accessibility(wait_s=10):
        res.notes.append("accessibility service not connected")
    clear_crashes()
    m0 = server_metrics()
    t0 = time.time()
    try:
        fn(res)
    except DeviceGone:
        raise
    except (Exception, SystemExit) as e:  # a harness problem in one scenario must not hide the others
        res.check(False, f"harness error: {type(e).__name__}: {e}")
    res.duration = time.time() - t0
    res.speed = speed(m0, server_metrics())
    try:
        crash = crash_log()
    except subprocess.TimeoutExpired:
        check_device()
        crash = ""
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
    # Leave the next scenario a clean home chat.
    sh("input keyevent KEYCODE_HOME")
    time.sleep(1)


def scorecard(results):
    rows = [("", "Scenario", "Result", "Tool calls", "Time", "Model speed")]
    for r in results:
        tools = ", ".join(r.tools) if r.tools else "-"
        verdict = "PASS" if r.passed else ("FAIL" if r.core else "FAIL (optional)")
        rows.append((r.key, r.title, verdict, tools[:70], f"{r.duration:.0f}s", r.speed or "-"))
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
    core = [r for r in results if r.core]
    optional = [r for r in results if not r.core and r.key != "0"]
    passed = sum(r.passed for r in core)
    tally = f"{passed}/{len(core)} core scenarios passed" + (
        f", {sum(r.passed for r in optional)}/{len(optional)} optional" if optional else ""
    )
    print(tally)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("## End-to-end tasks\n\n| | Scenario | Result | Tool calls | Time | Model speed |\n|---|---|---|---|---|---|\n")
            for r in results:
                verdict = "PASS" if r.passed else ("FAIL" if r.core else "FAIL (optional)")
                f.write(f"| {r.key} | {r.title} | {verdict} | {', '.join(r.tools) or '-'} | {r.duration:.0f}s | {r.speed or '-'} |\n")
            f.write(f"\n{tally}\n")
            for r in results:
                if not r.passed:
                    f.write(f"\n**{r.key}. {r.title}**\n\n" + "".join(f"- {n}\n" for n in r.notes))
    sys.stdout.flush()


if __name__ == "__main__":
    main()
