#!/usr/bin/env python3
"""One-to-one comparison: drives ONE installed Atom (old or new, release build) through the same tasks on a CI
emulator, against the same local model, and writes its measurements to a JSON file.

Runs only on GitHub's disposable runners (.github/workflows/compare.yml). Both apps are treated exactly alike:
- set up only through their own screens (Settings > Model providers > Add OpenAI-compatible provider), pointed at
  tools/compare/proxy.py, which forwards to llama-server and logs every request;
- prompts are typed into the chat composer and sent with the Send button, one new conversation per task;
- results are judged from device state (Do Not Disturb, alarms, the foreground app) and from what the model was
  asked and answered (the proxy log), never from app internals, so no debug hook is needed.

Usage: run.py <label> <out.json>      (the app under test must be installed as io.github.namanwtf.atom)
"""

import json
import os
import re
import shlex
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "screenshots"))
os.environ.setdefault("SHOT_DIR", "compare-shots")
os.environ.setdefault("ATOM_PKG", "io.github.namanwtf.atom")

import capture  # noqa: E402
from capture import adb, sh, shot  # noqa: E402

PKG = os.environ["ATOM_PKG"]
PROXY = os.environ.get("COMPARE_PROXY", "http://127.0.0.1:8081")
APP_BASE_URL = os.environ.get("COMPARE_APP_BASE_URL", "http://10.0.2.2:8081/v1")
MODEL = os.environ.get("MODEL_ALIAS", "qwen2.5-3b-instruct")
TASK_TIMEOUT = int(os.environ.get("COMPARE_TASK_TIMEOUT", "1200"))
QUIET_S = 25
IDLE_MIN = int(os.environ.get("COMPARE_IDLE_MIN", "10"))


def log(msg):
    print(f"[compare {time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ------------------------------------------------------------------ proxy log


def set_label(label):
    req = urllib.request.Request(PROXY + "/__label", data=label.encode(), method="POST")
    urllib.request.urlopen(req, timeout=10).read()


def proxy_entries(label=None, since=0.0):
    with urllib.request.urlopen(PROXY + "/__log", timeout=30) as r:
        lines = r.read().decode("utf-8", errors="replace").splitlines()
    out = []
    for ln in lines:
        try:
            e = json.loads(ln)
        except Exception:
            continue
        if (label is None or e.get("label") == label) and e.get("t_start", 0) >= since:
            out.append(e)
    return out


def prompt_tokens(e):
    u = e.get("usage") or {}
    if u.get("prompt_tokens"):
        return int(u["prompt_tokens"])
    t = e.get("timings") or {}
    if t.get("prompt_n") is not None:
        return int(t.get("prompt_n", 0)) + int(t.get("cache_n", 0) or 0)
    return None


# ------------------------------------------------------------------ UI


class Node:
    def __init__(self, el):
        self.text = el.get("text", "") or ""
        self.desc = el.get("content-desc", "") or ""
        self.cls = el.get("class", "") or ""
        self.rid = el.get("resource-id", "") or ""
        self.clickable = el.get("clickable") == "true"
        self.checkable = el.get("checkable") == "true"
        self.checked = el.get("checked") == "true"
        self.focused = el.get("focused") == "true"
        self.hint = el.get("hint", "") or ""
        b = [int(x) for x in re.findall(r"-?\d+", el.get("bounds", ""))]
        self.bounds = tuple(b) if len(b) == 4 else (0, 0, 0, 0)
        self.label = ""  # filled for edit fields: text of the nearest label

    @property
    def center(self):
        l, t, r, b = self.bounds
        return (l + r) // 2, (t + b) // 2

    @property
    def edit(self):
        return "EditText" in self.cls

    def __repr__(self):
        l, t, r, b = self.bounds
        kind = self.cls.split(".")[-1]
        flags = ("C" if self.clickable else "") + ("E" if self.edit else "") + ("x" if self.checked else "")
        return f"{kind}[{flags}] {self.text[:50]!r} {self.desc[:40]!r} {('hint=' + self.hint[:30]) if self.hint else ''} @{l},{t},{r},{b}"


def nodes():
    for _ in range(4):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1", timeout=60)
        raw = adb("exec-out", "cat", "/sdcard/ui.xml", timeout=60)
        try:
            root = ET.fromstring(raw)
            break
        except ET.ParseError:
            time.sleep(1)
    else:
        return []
    out = []

    def walk(el, inherited):
        n = Node(el)
        inner = [c for c in el if c.tag == "node"]
        texts = [c.get("text", "") for c in el.iter("node") if c is not el and c.get("text")]
        if n.edit:
            n.label = " ".join(t for t in texts if t != n.text)[:80]
        out.append(n)
        for c in inner:
            walk(c, inherited)

    for el in root:
        if el.tag == "node":
            walk(el, None)
    # A field's label is often a separate text just above it.
    labels = [x for x in out if x.text and not x.edit]
    for f in out:
        if f.edit and not f.label:
            above = [x for x in labels if x.bounds[3] <= f.bounds[1] + 10 and f.bounds[1] - x.bounds[3] < 140
                     and x.bounds[0] < f.bounds[2] and x.bounds[2] > f.bounds[0]]
            if above:
                f.label = max(above, key=lambda x: x.bounds[3]).text[:80]
    return out


def screen_report(tag):
    ns = nodes()
    print(f"=====UI {tag}=====")
    for n in ns:
        if n.text or n.desc or n.edit or n.checkable or (n.clickable and n.desc):
            print("  " + repr(n) + (f" label={n.label!r}" if n.edit else ""))
    print("=====END=====", flush=True)
    return ns


def find(pattern, ns=None, clickable_first=False):
    rx = re.compile(pattern, re.I)
    ns = ns if ns is not None else nodes()
    hits = [n for n in ns if n.bounds[2] > n.bounds[0] and (rx.search(n.text) or rx.search(n.desc))]
    if clickable_first:
        hits.sort(key=lambda n: not n.clickable)
    return hits[0] if hits else None


def tap_node(n, wait=1.5):
    x, y = n.center
    sh(f"input tap {x} {y}")
    time.sleep(wait)


def tap(pattern, wait=1.5, scroll=0):
    for i in range(scroll + 1):
        n = find(pattern)
        if n:
            tap_node(n, wait)
            return True
        if i < scroll:
            swipe_up()
    log(f"  no node matches /{pattern}/")
    return False


def swipe_up():
    sh("input swipe 540 1700 540 800 400")
    time.sleep(1.2)


def back(wait=1.2):
    sh("input keyevent KEYCODE_BACK")
    time.sleep(wait)


def type_text(text):
    # `input text` takes %s for spaces; quote the rest for the device shell.
    escaped = text.replace("%", "\\%").replace(" ", "%s")
    sh(f"input text {shlex.quote(escaped)}")
    time.sleep(0.8)


def hide_keyboard():
    if "mInputShown=true" in sh("dumpsys input_method | grep -E 'mInputShown'"):
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)


ANRS = {"count": 0}


def handle_anr():
    """An "isn't responding" dialog: counted, then Wait (the user's usual choice)."""
    n = find(r"isn.t responding")
    if not n:
        return False
    ANRS["count"] += 1
    log(f"  ANR dialog #{ANRS['count']}: {n.text!r}; tapping Wait")
    tap(r"^Wait$", 3)
    return True


def clear_focused():
    sh("input keyevent KEYCODE_MOVE_END")
    sh("input keyevent " + " ".join(["KEYCODE_DEL"] * 60))


def fill(field_pattern, value):
    rx = re.compile(field_pattern, re.I)
    for attempt in range(4):
        ns = nodes()
        fields = [n for n in ns if n.edit]
        hit = next((f for f in fields if rx.search(f.label) or rx.search(f.hint) or rx.search(f.text) or rx.search(f.desc)), None)
        if hit:
            tap_node(hit, 0.8)
            clear_focused()
            type_text(value)
            hide_keyboard()
            log(f"  filled /{field_pattern}/ ({hit.label or hit.hint or hit.text!r}) with {value!r}")
            return True
        swipe_up()
    log(f"  no field /{field_pattern}/; fields seen: {[(f.label, f.hint, f.text) for f in fields]}")
    return False


def foreground():
    out = sh("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -3")
    m = re.search(r"u0 ([\w.]+)/", out)
    return m.group(1) if m else ""


def launch_app(wait=6):
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
    time.sleep(wait)


DISMISS = r"^(Skip|Not now|Later|Continue|Get started|Start|Done|Finish|OK|Got it|Agree|Accept|I agree|Allow|While using the app|Close|No thanks|Maybe later)$"


def dismiss_dialogs(rounds=6):
    for _ in range(rounds):
        if handle_anr():
            continue
        n = find(DISMISS)
        if not n:
            return
        log(f"  dismissing {n.text or n.desc!r}")
        tap_node(n, 1.5)


# ------------------------------------------------------------------ setup


def components(action):
    out = sh(f"cmd package query-services --components -a {action}")
    return [ln.strip() for ln in out.splitlines() if ln.strip().startswith(PKG + "/")]


def grant_everything():
    a11y = components("android.accessibilityservice.AccessibilityService")
    listener = components("android.service.notification.NotificationListenerService")
    log(f"accessibility services: {a11y}; notification listeners: {listener}")
    cmds = [
        f"appops set {PKG} SYSTEM_ALERT_WINDOW ignore",  # no floating bubble in either app's runs
        f"appops set {PKG} SCHEDULE_EXACT_ALARM allow",
        f"cmd notification allow_dnd {PKG}",
        "settings put global window_animation_scale 0.5",
        "settings put system screen_off_timeout 1800000",
        "svc power stayon true",
        "cmd notification set_dnd off",
    ]
    cmds += [f"cmd notification allow_listener {c}" for c in listener]
    if a11y:
        cmds += [f"settings put secure enabled_accessibility_services {a11y[0]}", "settings put secure accessibility_enabled 1"]
    for c in cmds:
        out = sh(c).strip()
        log(f"$ {c}" + (f" -> {out[:200]}" if out else ""))
    # Every dangerous permission the app declares (install -g covers most; this catches the rest).
    perms = re.findall(r"^\s+(android\.permission\.[A-Z_]+): granted=false", sh(f"dumpsys package {PKG}"), re.M)
    for p in sorted(set(perms)):
        sh(f"pm grant {PKG} {p} 2>/dev/null")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")


def open_settings():
    """Home > conversation drawer > Settings, the same way a user goes."""
    for _ in range(3):
        if find(r"^(set up|Settings)$") and find(r"Model providers|LLM providers"):
            return True
        drawer = find(r"Conversation history|Open drawer|Menu|Conversations|History", clickable_first=True)
        if drawer:
            tap_node(drawer, 2)
        else:
            sh("input swipe 30 1200 800 1200 300")  # edge drag opens the side pane
            time.sleep(2)
        if tap(r"^(Settings|set up)$", 2.5):
            return True
        screen_report("looking-for-settings")
        back()
    return False


def configure_provider(label):
    """Adds the local server as an OpenAI-compatible provider, adds its model and makes it current."""
    launch_app()
    for _ in range(6):  # a slow first start can show "isn't responding" more than once
        if not handle_anr():
            break
        time.sleep(5)
    dismiss_dialogs()
    shot(f"{label}_00_first_screen")
    screen_report(f"{label}-first")
    if not open_settings():
        log("could not reach Settings")
        return False
    if not tap(r"^Model providers$", 2.5, scroll=4):
        screen_report(f"{label}-settings")
        return False
    if not tap(r"Add OpenAI-compatible provider", 2.5, scroll=4):
        screen_report(f"{label}-providers")
        return False
    screen_report(f"{label}-new-provider")
    fill(r"^(Display )?name|provider name|My provider", "Local")
    fill(r"Base URL|Endpoint|https://", APP_BASE_URL)
    fill(r"API key|Key", "sk-local-test")
    shot(f"{label}_01_provider_form")
    hide_keyboard()
    saved = tap(r"^(Save configuration|Save|Create|create|Add provider|Done|Add)$", 3, scroll=6)
    screen_report(f"{label}-after-save")
    if not saved:
        return False
    dismiss_dialogs(2)
    # Models: the "Model" tab of the provider, pull /models from the server, or add it by hand.
    if not find(r"^Model$"):
        row = find(r"^Local$")
        if row:
            tap_node(row, 2.5)
    tap(r"^Model$", 2)
    screen_report(f"{label}-model-tab")
    added = False
    if tap(r"pull from remote|Pull|Fetch models|Get models", 6):
        screen_report(f"{label}-pulled")
        if find(re.escape(MODEL)):
            if tap(r"^(Add|Add \d+|Import|Import \(\d+\)|Add all)$", 3):
                added = True
            else:
                n = find(re.escape(MODEL))
                tap_node(n, 1.5)
                added = tap(r"^(Add|Add \d+|Add \(\d+\)|Import|Import \(\d+\)|Done|OK)$", 3)
    if not added and tap(r"Add custom model|Add model", 2.5):
        screen_report(f"{label}-custom-model")
        fill(r"Model ID|model id|ID", MODEL)
        fill(r"Display name|Name", MODEL)
        added = tap(r"^(Add|Save|OK|Done|Confirm|Create)$", 2.5)
    screen_report(f"{label}-models")
    m = find("^" + re.escape(MODEL) + "$")
    if m:
        tap_node(m, 2)  # tapping a model makes it current
    shot(f"{label}_02_models")
    # Back to the home chat.
    for _ in range(5):
        back()
        if PKG not in foreground():
            launch_app(4)
            break
    launch_app(4)
    dismiss_dialogs(2)
    shot(f"{label}_03_home_configured")
    ns = screen_report(f"{label}-home")
    ok = any(MODEL.lower() in (n.text + n.desc).lower() or "local" in (n.text + n.desc).lower() for n in ns)
    log(f"configured: {added=} home shows the model: {ok}")
    return added or ok


# ------------------------------------------------------------------ tasks


def new_chat():
    launch_app(3)
    n = find(r"^New (chat|conversation)$|^New$", clickable_first=True)
    if n:
        tap_node(n, 2)
        return True
    drawer = find(r"Conversation history|Open drawer|Menu", clickable_first=True)
    if drawer:
        tap_node(drawer, 2)
        n = find(r"New (chat|conversation)", clickable_first=True)
        if n:
            tap_node(n, 2)
            return True
        back()
    log("  no new-chat button found; using the current chat")
    return False


def send(prompt):
    ns = nodes()
    box = [n for n in ns if n.edit]
    if not box:
        screen_report("no-composer")
        return False
    composer = max(box, key=lambda n: n.bounds[3])  # the lowest field is the composer
    tap_node(composer, 1)
    type_text(prompt)
    time.sleep(1)
    s = find(r"^Send$|^send$|Send message", clickable_first=True)
    if s is None:
        screen_report("no-send")
        sh("input keyevent KEYCODE_ENTER")
        return True
    tap_node(s, 1)
    return True


def wait_done(label, since, timeout, until=None):
    """Waits until the model's latest answer has no tool calls and no request followed it for QUIET_S."""
    start = time.time()
    seen = 0
    while time.time() - start < timeout:
        es = proxy_entries(label, since)
        for e in es[seen:]:
            calls = ",".join(c["name"] for c in e.get("tool_calls", [])) or "-"
            log(f"  request {len(es)}: {e.get('messages')} msgs, {e.get('tools')} tools, prompt {prompt_tokens(e)} tok, "
                f"{e.get('t_end', 0) - e.get('t_start', 0):.0f}s, calls {calls}, text {e.get('text', '')[:120]!r} {e.get('error') or ''}")
        seen = len(es)
        if until and until(es):
            return es, True
        if es:
            last = es[-1]
            if not last.get("tool_calls") and time.time() - last.get("t_end", 0) > QUIET_S:
                return es, True
        if not app_alive():
            log("  app process is gone")
            return es, False
        time.sleep(4)
    log(f"  timed out after {timeout}s")
    return proxy_entries(label, since), False


def app_alive():
    return bool(sh(f"pidof {PKG}").strip())


def crash_lines():
    text = adb("logcat", "-d", "-b", "crash", timeout=60).decode(errors="replace")
    return [ln for ln in text.splitlines() if PKG in ln or "FATAL EXCEPTION" in ln]


def all_calls(es):
    return [c["name"] for e in es for c in e.get("tool_calls", [])]


def final_text(es):
    for e in reversed(es):
        if e.get("text") and not e.get("tool_calls"):
            return e["text"]
    return es[-1].get("text", "") if es else ""


def zen():
    return sh("settings get global zen_mode").strip()


def app_alarms():
    out = sh("dumpsys alarm")
    return len(re.findall(r"\b" + re.escape(PKG) + r"\b", out.split("Pending alarm batches")[-1][:200000] if "Pending alarm batches" in out else out))


def t_hi(ctx):
    return True, "any answer"


def t_dnd_pre(ctx):
    sh("cmd notification set_dnd off")
    time.sleep(1)


def t_dnd(ctx):
    z = zen()
    sh("cmd notification set_dnd off")
    return z not in ("0", "", "null"), f"zen_mode after: {z}"


def t_notif_pre(ctx):
    sh("cmd notification post -S bigtext -t 'Dinner' tag1 'Pizza at 8'")
    time.sleep(3)


def t_notif(ctx):
    ok = re.search(r"pizza|dinner", ctx["answer"], re.I) is not None
    return ok, "answer mentions Pizza or Dinner"


def t_sched_pre(ctx):
    ctx["alarms_before"] = app_alarms()


def t_sched(ctx):
    after = app_alarms()
    called = [c for c in ctx["calls"] if re.search(r"sched|task|remind|automation|cron", c, re.I)]
    return bool(called) and after > ctx["alarms_before"], f"scheduling calls {called or 'none'}; app alarms {ctx['alarms_before']} -> {after}"


def t_web(ctx):
    ok = re.search(r"documentation|illustrative|permission|literature|coordination", ctx["answer"], re.I) is not None
    return ok, "answer describes the example.com text"


def t_open(ctx):
    fg = foreground()
    return fg == "com.android.settings", f"foreground after: {fg}"


def t_shell(ctx):
    called = [c for c in ctx["calls"] if re.search(r"shell|terminal|command|exec|run", c, re.I)]
    return bool(called) and "42" in ctx["answer"], f"shell calls {called or 'none'}; answer has 42: {'42' in ctx['answer']}"


TASKS = [
    # key, prompt, pre, check
    ("hi", "hi", None, t_hi),
    ("dnd", "Turn on Do Not Disturb.", t_dnd_pre, t_dnd),
    ("notification", "What was my last notification about?", t_notif_pre, t_notif),
    ("schedule", "Every day at 9am remind me to drink water.", t_sched_pre, t_sched),
    ("web", "Read the page https://example.com and tell me what it says.", None, t_web),
    ("open_app", "Open the Settings app.", None, t_open),
    ("shell", "Run the shell command echo $((6*7)) and tell me the output.", None, t_shell),
]


def run_task(label, key, prompt, pre, check):
    tag = f"{label}-{key}"
    log(f"===== {tag}: {prompt!r} =====")
    set_label(tag)
    adb("logcat", "-b", "crash", "-c")
    new_chat()
    ctx = {}
    if pre:
        pre(ctx)
    if PKG not in foreground():
        launch_app(3)
    t0 = time.time()
    sent = send(prompt)
    es, finished = wait_done(tag, t0 - 1, TASK_TIMEOUT) if sent else ([], False)
    t1 = max([e.get("t_end", t0) for e in es] or [time.time()])
    ctx.update(calls=all_calls(es), answer=final_text(es))
    ok, why = check(ctx) if es else (False, "no request reached the model")
    crashes = crash_lines()
    shot(f"{label}_task_{key}")
    res = {
        "task": key,
        "prompt": prompt,
        "passed": bool(ok and finished and not crashes),
        "finished": finished,
        "why": why,
        "requests": len(es),
        "first_prompt_tokens": prompt_tokens(es[0]) if es else None,
        "total_prompt_tokens": sum(prompt_tokens(e) or 0 for e in es),
        "tools_offered_first": es[0].get("tools") if es else None,
        "tool_schema_chars_first": es[0].get("tool_schema_chars") if es else None,
        "system_chars_first": es[0].get("system_chars") if es else None,
        "seconds": round(t1 - t0, 1),
        "calls": ctx["calls"],
        "answer": ctx["answer"][:400],
        "crashes": len(crashes),
        "crash_head": crashes[:20],
        "errors": [e.get("error") for e in es if e.get("error")][:3],
    }
    log(f"{tag}: {'PASS' if res['passed'] else 'FAIL'} ({why}); {res['requests']} requests, first prompt {res['first_prompt_tokens']} tok, "
        f"total {res['total_prompt_tokens']} tok, {res['seconds']}s, calls {res['calls']}, answer {res['answer'][:160]!r}")
    # Leave Settings or another app before the next task.
    sh("input keyevent KEYCODE_HOME")
    time.sleep(1)
    return res


def stop_task(label):
    """Starts a long task and taps the app's own Stop button once the first tool call is under way."""
    tag = f"{label}-stop"
    log(f"===== {tag} =====")
    set_label(tag)
    adb("logcat", "-b", "crash", "-c")
    new_chat()
    t0 = time.time()
    send("Check the device status, then list my installed apps, then read the screen, then check the time and location, "
         "and finally write a detailed report about everything you found.")
    es, _ = wait_done(tag, t0 - 1, TASK_TIMEOUT, until=lambda es: any(e.get("tool_calls") for e in es))
    if PKG not in foreground():
        launch_app(2)
    stopped = tap(r"^Stop$|^stop$|Stop (task|generating|run)", 2)
    if not stopped:
        screen_report(f"{label}-no-stop")
    time.sleep(QUIET_S)
    n_before = len(proxy_entries(tag, t0 - 1))
    time.sleep(30)
    n_after = len(proxy_entries(tag, t0 - 1))
    crashes = crash_lines()
    alive = app_alive()
    shot(f"{label}_task_stop")
    res = {"task": "stop", "passed": stopped and alive and not crashes and n_after == n_before, "stop_tapped": stopped,
           "alive": alive, "requests_after_stop": n_after - n_before, "crashes": len(crashes), "crash_head": crashes[:20]}
    log(f"{tag}: {res}")
    sh("input keyevent KEYCODE_HOME")
    return res


# ------------------------------------------------------------------ idle battery proxy


def idle_battery(label):
    """10 min idle with the screen off and the charger 'unplugged': the app's wakelocks, wakeup alarms, CPU and jobs.

    An emulator has no real battery or radio, so this measures what the app asks the system to do while idle, not
    milliamp-hours.
    """
    m = re.search(r"\b(?:appId|userId)=(\d+)", sh(f"dumpsys package {PKG}"))
    uid = m.group(1) if m else "?"
    sh("input keyevent KEYCODE_HOME")
    sh("svc power stayon false")
    sh("dumpsys battery unplug")
    sh("dumpsys batterystats --reset")
    alarms_before = sh("dumpsys alarm")
    sh("input keyevent KEYCODE_SLEEP")
    log(f"idle: screen off for {IDLE_MIN} min (uid {uid})")
    time.sleep(IDLE_MIN * 60)
    checkin = sh("dumpsys batterystats --checkin", timeout=180)
    human = sh(f"dumpsys batterystats {PKG}", timeout=180)
    alarm_dump = sh("dumpsys alarm")
    sh("input keyevent KEYCODE_WAKEUP")
    sh("dumpsys battery reset")
    sh("svc power stayon true")
    sh("wm dismiss-keyguard")
    rows = [ln.split(",") for ln in checkin.splitlines() if ln.startswith(f"9,{uid},l,")]
    wl_ms = wl_n = wua = cpu_user = cpu_sys = jobs = 0
    for r in rows:
        kind = r[3]
        try:
            if kind == "wl":  # name, full ms, f, count, partial ms, p, count, ... (the name may hold commas)
                p = len(r) - 1 - r[::-1].index("p")
                wl_ms += int(r[p - 1])
                wl_n += int(r[p + 1])
            elif kind == "wua":
                wua += int(r[-1])
            elif kind == "cpu":
                cpu_user += int(r[4])
                cpu_sys += int(r[5])
            elif kind == "jb":
                jobs += int(r[-1]) if r[-1].isdigit() else 0
        except (ValueError, IndexError):
            pass
    print(f"=====BATTERY {label}=====")
    for r in rows:
        print("  " + ",".join(r)[:200])
    for ln in human.splitlines():
        if re.search(r"Wake lock|Wakeup alarm|Cpu times|Job |Foreground|wake", ln, re.I):
            print("  " + ln.strip()[:200])
    print("=====END=====", flush=True)
    pending = len(re.findall(re.escape(PKG), alarm_dump))
    return {"minutes": IDLE_MIN, "uid": uid, "wakelock_ms": wl_ms, "wakelock_count": wl_n, "wakeup_alarms": wua,
            "cpu_ms": cpu_user + cpu_sys, "jobs": jobs, "alarm_mentions_after": pending,
            "alarm_mentions_before": len(re.findall(re.escape(PKG), alarms_before))}


# ------------------------------------------------------------------ main


def main():
    label, out = sys.argv[1], sys.argv[2]
    result = {"label": label, "package": PKG}
    info = sh(f"dumpsys package {PKG} | grep -E 'versionName|versionCode' | head -2")
    result["version"] = " ".join(info.split())
    log(f"app under test: {label} {result['version']}")
    grant_everything()
    set_label(f"{label}-setup")
    result["configured"] = configure_provider(label)
    result["setup_requests"] = len(proxy_entries(f"{label}-setup"))
    tasks = result["tasks"] = []

    def save():  # after every task, so a job that runs out of time still reports what finished
        with open(out, "w") as f:
            json.dump(result, f, indent=1)

    save()
    if result["configured"]:
        for key, prompt, pre, check in TASKS:
            try:
                tasks.append(run_task(label, key, prompt, pre, check))
            except Exception as e:  # one harness problem must not hide the other tasks
                log(f"harness error in {key}: {type(e).__name__}: {e}")
                tasks.append({"task": key, "passed": False, "why": f"harness error: {e}"})
            save()
        try:
            tasks.append(stop_task(label))
        except Exception as e:
            tasks.append({"task": "stop", "passed": False, "why": f"harness error: {e}"})
        save()
    result["crash_buffer_total"] = len(crash_lines())
    result["anr_dialogs"] = ANRS["count"]
    try:
        result["idle"] = idle_battery(label)
    except Exception as e:
        result["idle"] = {"error": str(e)}
    save()
    log(f"wrote {out}")


if __name__ == "__main__":
    main()
