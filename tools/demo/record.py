#!/usr/bin/env python3
"""Screen recordings of real ATOM runs for the launch posts, made on a CI emulator (.github/workflows/demo-recordings.yml).

Nothing is staged: each clip is the debug build doing a real task, typed into the app the way a person would, against
DeepSeek's API. The app talks to tools/demo/key_proxy.py on the runner (http://10.0.2.2:8080/v1, no key in the app);
the relay adds the key and forwards each request unchanged. The only app settings made here are the provider itself
(name, model, context window) and its "extra body" {"max_tokens": N}, a setting any user can make in the provider's
advanced options, because the app's default output budget (2048 tokens) is too small for a whole web page in one file.

Recordings are full resolution, printed into the job log as base64 between =====SHOT <name>.mp4===== and =====END=====.
Each clip also logs "[mark] <clip> t=<seconds> <event>" lines, timed from the start of its recording, so an editor
can find each step and label the waiting time that is cut.

Safety rules learnt from the last run: Back is pressed only to close the keyboard while it is shown; before typing,
ATOM must be the foreground app with its composer on screen; a new prompt is sent only when the previous run has
finished (a run that overstays its time is stopped and waited for).

Usage: python3 tools/demo/record.py [clip ...]   (default: website game bill multiapp research)
"""

import base64
import datetime
import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "e2e"))
sys.path.insert(0, os.path.join(HERE, "..", "screenshots"))
os.environ.setdefault("SHOT_DIR", "demo-out")

import run as e2e  # noqa: E402  (shared app hook, prompt and polling helpers)
from capture import PKG, ACTIVITY, adb, sh, shot, launch  # noqa: E402

OUT = os.environ["SHOT_DIR"]
W, H = 1080, 2400
MODEL = os.environ.get("DEMO_MODEL", "deepseek-chat")
CTX = int(os.environ.get("DEMO_CTX", "131072"))
MAX_OUT = int(os.environ.get("DEMO_MAX_TOKENS", "8192"))
RUN_WAIT = int(os.environ.get("DEMO_RUN_WAIT", "1200"))

PROMPTS = {
    "website": ("Make me a one-page website for a specialty coffee shop called Ember: a striking full-screen hero, "
                "a menu with prices, opening hours, a map link, a warm dark theme and smooth scroll animations."),
    "website2": "Add a table booking form and make the menu two columns.",
    "game": ("Build me a neon Snake game I can play with swipes anywhere on the screen, with a glowing snake "
             "and a high score."),
    "bill": ("Make me a bill-splitting app for a dinner with friends: enter the total, tip and number of people, "
             "show each person's share, glassy dark design."),
    "multiapp": "Priya texted me about dinner. Check my calendar for that evening and reply to her with when I'm free.",
    "research": ("Find the 3 best-rated sci-fi books of 2025, put them in a table on a page, and remind me at 8pm "
                 "to buy one."),
}


def log(msg):
    e2e.log(msg)


# ------------------------------------------------------------------ recording


class Recorder:
    """screenrecord stops after 180 s, so a long clip is recorded as consecutive segments."""

    def __init__(self, name):
        self.name = name
        self.parts = []
        self.proc = None
        self.t0 = None

    def _start(self):
        path = f"/sdcard/rec_{self.name}_{len(self.parts)}.mp4"
        self.parts.append(path)
        self.proc = subprocess.Popen(
            ["adb", "shell", "screenrecord", "--size", f"{W}x{H}", "--bit-rate", "12000000", "--time-limit", "170", path]
        )

    def start(self):
        self._start()
        self.t0 = time.time()
        time.sleep(1.5)
        self.mark("recording")

    def mark(self, event):
        print(f"[mark] {self.name} t={time.time() - self.t0:.1f} {event}")
        sys.stdout.flush()

    def tick(self):
        if self.proc and self.proc.poll() is not None:
            self.mark(f"segment {len(self.parts)} starts")
            self._start()

    def wait(self, seconds):
        end = time.time() + seconds
        while time.time() < end:
            self.tick()
            time.sleep(min(0.5, max(0.05, end - time.time())))

    def stop(self):
        self.mark("stop")
        sh("pkill -INT screenrecord")
        if self.proc:
            try:
                self.proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        time.sleep(2)

    def collect(self):
        local = []
        for i, p in enumerate(self.parts):
            dst = os.path.join(OUT, f"{self.name}_{i}.mp4")
            subprocess.run(["adb", "pull", p, dst], capture_output=True, timeout=300)
            sh(f"rm -f {p}")
            if os.path.exists(dst) and os.path.getsize(dst) > 0:
                local.append(dst)
        if not local:
            return None
        lst = os.path.join(OUT, f"{self.name}.txt")
        with open(lst, "w") as f:
            for p in local:
                f.write(f"file '{os.path.abspath(p)}'\n")
        out = os.path.join(OUT, f"{self.name}.mp4")
        # Full size, 30 fps, real time. Cuts are made in editing, and labelled there.
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst,
             "-vf", "fps=30,scale=1080:-2:flags=lanczos", "-c:v", "libx264", "-crf", "21", "-preset", "veryfast",
             "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", out],
            check=False,
        )
        for p in local:
            os.remove(p)
        return out if os.path.exists(out) else None


def print_file(path):
    data = open(path, "rb").read()
    print(f"=====SHOT {os.path.basename(path)}=====")
    enc = base64.b64encode(data).decode()
    for i in range(0, len(enc), 16000):
        print(enc[i : i + 16000])
    print("=====END=====")
    sys.stdout.flush()


def finish(rec):
    rec.stop()
    out = rec.collect()
    if out:
        log(f"{rec.name}: {os.path.getsize(out)} bytes")
        print_file(out)
    else:
        log(f"{rec.name}: no recording")


# ------------------------------------------------------------------ screen state


def nodes():
    """The UI hierarchy as dicts: text, desc, cls, pkg, bounds."""
    for _ in range(3):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        raw = adb("exec-out", "cat", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(raw)
            break
        except ET.ParseError:
            time.sleep(0.8)
    else:
        return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4:
            continue
        out.append(dict(text=n.get("text", "") or "", desc=n.get("content-desc", "") or "", cls=n.get("class", ""),
                        pkg=n.get("package", ""), bounds=tuple(int(x) for x in b)))
    return out


def match(ns, pattern, pkg=None):
    rx = re.compile(pattern, re.I)
    for n in ns:
        if pkg and n["pkg"] != pkg:
            continue
        if rx.search(n["text"]) or rx.search(n["desc"]):
            return n
    return None


def match_last(ns, pattern, pkg=None):
    hit = None
    rx = re.compile(pattern, re.I)
    for n in ns:
        if pkg and n["pkg"] != pkg:
            continue
        if rx.search(n["text"]) or rx.search(n["desc"]):
            hit = n
    return hit


def tap_node(n):
    b = n["bounds"]
    sh(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}")


def swipe(x1, y1, x2, y2, ms):
    sh(f"input swipe {x1} {y1} {x2} {y2} {ms}")


def fg_package():
    out = sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 2")
    m = re.search(r"u0 ([A-Za-z0-9_.]+)/", out)
    return m.group(1) if m else ""


def ime_shown():
    out = sh("dumpsys input_method | grep -E 'mInputShown|InputViewShown'")
    return "mInputShown=true" in out or "InputViewShown=true" in out


def hide_keyboard():
    """Back only while the keyboard is up: then it closes the keyboard and nothing else."""
    if ime_shown():
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1.0)


def to_root():
    """ATOM's root (chat) screen through its own assist entry point, which resets its navigation; never the Back key."""
    sh(f"am start -n {ACTIVITY} -a android.intent.action.ASSIST")
    time.sleep(3)


def composer(ns):
    """ATOM's message field (while empty it has no accessibility text of its own)."""
    edits = [n for n in ns if n["pkg"] == PKG and n["cls"] == "android.widget.EditText"]
    return max(edits, key=lambda n: n["bounds"][1]) if edits else None


def in_preview(ns=None):
    return match(ns if ns is not None else nodes(), "^Close preview$", PKG) is not None


def dismiss_anr(ns):
    if match(ns, "isn.t responding"):
        w = match(ns, "^Wait$")
        if w:
            log("dismissing an 'isn't responding' dialog")
            tap_node(w)
            time.sleep(1.5)
            return True
    return False


def ensure_chat():
    """ATOM's chat screen in front with its composer; True when so. Never presses Back outside ATOM."""
    for attempt in range(5):
        fg = fg_package()
        if fg != PKG:
            log(f"foreground is {fg or '?'}; launching ATOM")
            launch(wait=3)
        ns = nodes()
        if dismiss_anr(ns):
            continue
        if in_preview(ns):
            tap_node(match(ns, "^Close preview$", PKG))
            time.sleep(2)
            continue
        if composer(ns) and match(ns, "^Conversations$", PKG):
            return True
        log(f"not on the chat screen (attempt {attempt + 1}); resetting ATOM's navigation")
        hide_keyboard()
        to_root()
    return False


def new_chat():
    if not ensure_chat():
        return False
    ns = nodes()
    more = match(ns, "^More options$", PKG)
    if more:
        tap_node(more)
        time.sleep(1.2)
        item = match(nodes(), "^New conversation$", PKG)
        if item:
            tap_node(item)
            time.sleep(1.5)
        else:
            log("new_chat: no 'New conversation' item")
            hide_keyboard()
    return ensure_chat()


# ------------------------------------------------------------------ typing and running


def type_words(text):
    """Two words per call: a long `input text` loses characters in the emulator's text field."""
    words = text.split(" ")
    for i in range(0, len(words), 2):
        chunk = " ".join(words[i : i + 2])
        sh("input text " + shlex.quote(chunk.replace(" ", "%s")))
        if i + 2 < len(words):
            sh("input keyevent 62")
        time.sleep(0.12)


def type_prompt(rec, prompt):
    """Types the request into ATOM's composer and taps Send, as a person would. False if that could not be done."""
    if not ensure_chat():
        log("type_prompt: ATOM's chat is not in front; not typing")
        return False
    field = composer(nodes())
    tap_node(field)
    rec.wait(1.2)
    if fg_package() != PKG:
        log("type_prompt: ATOM lost the foreground; not typing")
        return False
    before = e2e.run_ids()
    rec.mark("typing")
    type_words(prompt)
    tail = prompt[-12:]
    typed = False
    deadline = time.time() + 30
    while time.time() < deadline:
        f = composer(nodes())
        if f and f["text"].rstrip().endswith(tail):
            typed = True
            break
        rec.wait(1)
    log(f"composer has the whole prompt: {typed}")
    if not typed:
        sh("input keyevent KEYCODE_MOVE_END")
        sh("input keyevent " + " ".join(["67"] * (len(prompt) + 10)))
        return False
    rec.mark("typed")
    rec.wait(0.6)
    send = match(nodes(), "^Send$", PKG)
    if send is None:
        log("type_prompt: no Send button")
        return False
    tap_node(send)
    rec.mark("sent")
    for _ in range(10):
        rec.wait(1)
        if e2e.run_ids() - before:
            return True
    log("type_prompt: Send did not start a run")
    return False


def wait_run(rec, w, timeout=RUN_WAIT):
    """Waits for the run to end, marking each tool call; stops it if it overstays. Returns the last run state."""
    t0 = time.time()
    seen = 0
    run = None
    phase = None
    timed_out = True
    while time.time() - t0 < timeout:
        rec.tick()
        run = w.poll()
        if run:
            calls = run.get("toolCalls", [])
            for c in calls[seen:]:
                rec.mark(f"tool {c['name']}")
            seen = len(calls)
            if run["phase"] != phase:
                phase = run["phase"]
                rec.mark(f"phase {phase}")
            pending = run.get("pending")
            if pending:
                # Allowed the way a person would, on ATOM's own prompt; from another app, through the hook.
                allow = match(nodes(), "^Allow$", PKG) if fg_package() == PKG else None
                if allow:
                    rec.mark("allow (tap)")
                    tap_node(allow)
                else:
                    rec.mark("allow (hook)")
                    e2e.call("approve", pending["id"])
                rec.wait(1.5)
            if not run["active"]:
                rec.mark(f"done outcome={run.get('outcome')} engine={run.get('engine')}")
                timed_out = False
                break
        rec.wait(2)
    if timed_out and run and run.get("active"):
        rec.mark("timed out: stopping the run")
        e2e.call("stop", run["conversationId"])
        end = time.time() + 90
        while time.time() < end:
            r = w.poll()
            if r and not r["active"]:
                run = r
                break
            rec.wait(3)
        run["timedOut"] = True
    if run:
        log(f"{rec.name}: tools {e2e.names(run)} engine={run.get('engine')} outcome={run.get('outcome')} "
            f"tokens in/out {run.get('inputTokens')}/{run.get('outputTokens')} error={run.get('error')}")
        for c in run.get("toolCalls", []):
            res = (c.get("result") or "").replace("\n", " ")[:220]
            log(f"    {c['name']} {c.get('args', '')[:140]!r} -> {'ERROR ' if c.get('isError') else ''}{res}")
        log(f"  answer: {e2e.answer(run)[:700]!r}")
    return run


def run_ok(run):
    return bool(run) and not run.get("active") and not run.get("timedOut") and not run.get("error")


def task(rec, prompt, timeout=RUN_WAIT):
    w = e2e.Watch(e2e.run_ids(), approve=False)
    if not type_prompt(rec, prompt):
        return None
    return wait_run(rec, w, timeout)


# ------------------------------------------------------------------ the website preview


def open_preview(rec):
    """The site's live preview in front (it opens by itself during a run; else the chat card's Open preview)."""
    for _ in range(3):
        ns = nodes()
        if in_preview(ns):
            return True
        if fg_package() != PKG:
            launch(wait=3)
            continue
        card = match_last(ns, "^Open preview$", PKG)
        if card:
            rec.mark("open preview (tap)")
            tap_node(card)
            rec.wait(4)
            continue
        hide_keyboard()
        swipe(540, 900, 540, 1700, 300)  # the card may be above the last answer
        rec.wait(1.5)
    return in_preview()


def tour(rec, steps=7, pause=1.6):
    """Scrolls slowly down the page, as a person reading it, then back to the top."""
    rec.mark("tour")
    for _ in range(steps):
        swipe(540, 1850, 540, 1050, 1100)
        rec.wait(pause)
    rec.mark("tour end")
    rec.wait(1)
    for _ in range(5):
        swipe(540, 700, 540, 2000, 160)
        rec.wait(0.4)
    rec.mark("back at top")
    rec.wait(1.5)


def close_preview():
    n = match(nodes(), "^Close preview$", PKG)
    if n:
        tap_node(n)
        time.sleep(2)


def page_nodes():
    """Nodes of the page inside the preview (the WebView's own tree), below the preview's top bar."""
    return [n for n in nodes() if n["pkg"] == PKG and n["bounds"][1] > 260]


# ------------------------------------------------------------------ clips


def clip_website():
    name = "website"
    if not new_chat():
        log("website: no chat screen")
        return
    rec = Recorder(name)
    rec.start()
    rec.wait(1.5)
    run = task(rec, PROMPTS["website"])
    rec.wait(2)
    if run_ok(run) and open_preview(rec):
        rec.mark("preview")
        rec.wait(3)
        shot("demo_website_preview")
        tour(rec, steps=8)
        shot("demo_website_top")
    close_preview()
    rec.mark("follow-up")
    run2 = task(rec, PROMPTS["website2"]) if run_ok(run) else None
    rec.wait(2)
    if run_ok(run2) and open_preview(rec):
        rec.mark("preview 2")
        rec.wait(3)
        shot("demo_website2_preview")
        tour(rec, steps=10)
        shot("demo_website2_top")
    finish(rec)


SWIPES = {
    "up": (540, 1500, 540, 1050),
    "down": (540, 1100, 540, 1550),
    "left": (760, 1300, 320, 1300),
    "right": (320, 1300, 760, 1300),
}


def play_snake(rec, seconds):
    """Plays with real swipes: loops of changing size around the board's middle, restarting after a game over."""
    rec.mark("play")
    order = ["up", "left", "down", "right"]
    sides = [0.55, 0.7, 0.9, 0.6, 1.1, 0.75, 0.5, 0.95]
    end = time.time() + seconds
    i = 0
    last_check = time.time()
    while time.time() < end:
        x1, y1, x2, y2 = SWIPES[order[i % 4]]
        swipe(x1, y1, x2, y2, 70)
        rec.wait(sides[i % len(sides)])
        i += 1
        if time.time() - last_check > 6:
            last_check = time.time()
            ns = page_nodes()
            if match(ns, r"game\s*over|you (died|lost)|crashed"):
                rec.mark("game over")
                shot("demo_game_over")
                rec.wait(1.5)
                again = match(ns, r"^(play again|restart|try again|start|play|new game|tap to (start|play|restart))")
                if again:
                    tap_node(again)
                else:
                    sh("input tap 540 1300")
                rec.mark("restart")
                rec.wait(1)
                i = 0


def clip_game():
    name = "game"
    if not new_chat():
        log("game: no chat screen")
        return
    rec = Recorder(name)
    rec.start()
    rec.wait(1.5)
    run = task(rec, PROMPTS["game"])
    rec.wait(2)
    if run_ok(run) and open_preview(rec):
        rec.mark("preview")
        rec.wait(2.5)
        shot("demo_game_preview")
        start = match(page_nodes(), r"^(start|play|start game|tap to (start|play)|press start|swipe to start)")
        if start:
            rec.mark("start (button)")
            tap_node(start)
        else:
            rec.mark("start (tap)")
            sh("input tap 540 1300")
        rec.wait(0.8)
        play_snake(rec, 40)
        shot("demo_game_played")
        rec.wait(2)
    finish(rec)


def fields():
    edits = [n for n in page_nodes() if n["cls"] == "android.widget.EditText"]
    edits.sort(key=lambda n: (n["bounds"][1], n["bounds"][0]))
    return edits


def fill(n, value, rec):
    tap_node(n)
    rec.wait(0.8)
    sh("input keyevent KEYCODE_MOVE_END")
    sh("input keyevent " + " ".join(["67"] * 12))
    rec.wait(0.3)
    for ch in value:
        sh("input text " + shlex.quote(ch))
        rec.wait(0.15)
    rec.wait(0.8)


def clip_bill():
    name = "bill"
    if not new_chat():
        log("bill: no chat screen")
        return
    rec = Recorder(name)
    rec.start()
    rec.wait(1.5)
    run = task(rec, PROMPTS["bill"])
    rec.wait(2)
    if run_ok(run) and open_preview(rec):
        rec.mark("preview")
        rec.wait(3)
        shot("demo_bill_preview")
        first = fields()
        log(f"bill: {len(first)} fields: " + " | ".join(f"{e['text'][:20]!r}@{e['bounds']}" for e in first))
        plan = ["186.40", "18", "4"] if len(first) >= 3 else ["186.40", None, "4"]
        k = 0
        for value in plan:
            if value is None:
                tip = match(page_nodes(), r"^\s*(18|20)\s*%\s*$")
                if tip:
                    hide_keyboard()
                    rec.mark("tip button")
                    tap_node(match(page_nodes(), r"^\s*(18|20)\s*%\s*$") or tip)
                    rec.wait(1)
                continue
            fs = fields()
            if k >= len(fs):
                break
            if fs[k]["bounds"][3] > 1450 and ime_shown():
                hide_keyboard()
                fs = fields()
            rec.mark(f"field {k}")
            fill(fs[k], value, rec)
            k += 1
        log(f"bill: filled {k} fields")
        hide_keyboard()
        rec.wait(1)
        btn = match(page_nodes(), r"^(split|calculate|split it|split the bill|split bill)\b")
        if btn:
            rec.mark("calculate (tap)")
            tap_node(btn)
        rec.wait(3)
        shot("demo_bill_result")
        swipe(540, 1700, 540, 1200, 900)
        rec.wait(3)
        shot("demo_bill_result2")
    finish(rec)


def device_now():
    secs = int(sh("date +%s").strip())
    off = sh("date +%z").strip()  # e.g. +0000
    offset = 0
    if re.match(r"^[+-]\d{4}$", off):
        offset = (-1 if off.startswith("-") else 1) * (int(off[1:3]) * 3600 + int(off[3:5]) * 60)
    return secs, offset


def seed_messages():
    """A real incoming SMS on the emulator (through its modem), Priya as a contact, and tomorrow evening's calendar."""
    sh("content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:")
    raw = sh("content query --uri content://com.android.contacts/raw_contacts --projection _id").strip().splitlines()
    rid = re.findall(r"_id=(\d+)", raw[-1]) if raw else []
    if rid:
        r = rid[0]
        sh(f"content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:{r} "
           "--bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Priya --bind data2:s:Priya")
        sh(f"content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:{r} "
           "--bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:5551234 --bind data2:i:2")
    log("contacts: " + sh("content query --uri content://com.android.contacts/data/phones --projection display_name:data1").strip()[:300])
    subprocess.run(["adb", "emu", "sms", "send", "5551234",
                    "Hey! Are you free for dinner tomorrow evening? Thinking that new ramen place. - Priya"],
                   capture_output=True, timeout=60)
    time.sleep(5)
    uri = "content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=Phone&account_type=LOCAL"
    binds = ("--bind account_name:s:Phone --bind account_type:s:LOCAL --bind name:s:Personal "
             "--bind calendar_displayName:s:Personal --bind calendar_access_level:i:700 --bind visible:i:1 "
             "--bind sync_events:i:1 --bind ownerAccount:s:Phone --bind calendar_color:i:-12303292 --bind isPrimary:i:1")
    sh(f"content insert --uri '{uri}' {binds}")
    cals = sh("content query --uri content://com.android.calendar/calendars --projection _id:calendar_displayName")
    m = re.search(r"_id=(\d+), calendar_displayName=Personal", cals)
    cal = m.group(1) if m else "1"
    secs, offset = device_now()
    local = datetime.datetime.fromtimestamp(secs + offset, datetime.timezone.utc).replace(tzinfo=None)
    tomorrow = (local + datetime.timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    tz = sh("getprop persist.sys.timezone").strip() or "UTC"

    def at(h, mnt=0):
        t = tomorrow + datetime.timedelta(hours=h, minutes=mnt)
        return (int((t - datetime.datetime(1970, 1, 1)).total_seconds()) - offset) * 1000

    for title, a, b in (("Design review", at(16), at(17, 30)), ("Gym", at(17, 45), at(19)), ("Call with Mum", at(19), at(19, 30))):
        sh(f"content insert --uri content://com.android.calendar/events --bind calendar_id:i:{cal} "
           f"--bind title:s:{shlex.quote(title)} --bind dtstart:l:{a} --bind dtend:l:{b} --bind eventTimezone:s:{tz}")
    log(f"calendar: {cals.strip()[:200]}; tomorrow={tomorrow.date()} tz={tz}")
    log("events: " + sh("content query --uri content://com.android.calendar/events --projection title:dtstart:dtend").strip()[:500])
    log("sms inbox: " + sh("content query --uri content://sms/inbox --projection address:body").strip()[:300])


def warm_messages():
    """Opens the Messages app once before recording, so a first-run screen is not what the agent meets."""
    sh("am start -a android.intent.action.MAIN -c android.intent.category.APP_MESSAGING")
    time.sleep(6)
    log(f"messages package: {fg_package()}")
    shot("demo_messages_first_open")
    n = match(nodes(), r"^(Got it|Not now|No thanks|Skip|Maybe later|Dismiss)$")
    if n:
        log(f"messages: first-run prompt '{n['text'] or n['desc']}', dismissed")
        tap_node(n)
        time.sleep(2)
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)


def clip_multiapp():
    name = "multiapp"
    seed_messages()
    warm_messages()
    if not new_chat():
        log("multiapp: no chat screen")
        return
    rec = Recorder(name)
    rec.start()
    rec.wait(1.5)
    task(rec, PROMPTS["multiapp"])
    rec.wait(4)
    shot("demo_multiapp_end")
    sent = sh("content query --uri content://sms --projection address:body:type").strip()
    log("sms after run: " + sent[:800])
    log(f"multiapp: a reply was sent: {bool(re.search(r'type=2', sent))}")
    # The proof on the phone itself: Priya's thread in the Messages app.
    rec.mark("messages thread")
    sh("am start -a android.intent.action.SENDTO -d sms:5551234")
    rec.wait(5)
    shot("demo_multiapp_thread")
    rec.wait(2)
    sh("input keyevent KEYCODE_HOME")
    rec.wait(1)
    finish(rec)


def clip_research():
    name = "research"
    if not new_chat():
        log("research: no chat screen")
        return
    rec = Recorder(name)
    rec.start()
    rec.wait(1.5)
    run = task(rec, PROMPTS["research"])
    rec.wait(3)
    shot("demo_research_done")
    if run_ok(run) and "site_preview" in e2e.names(run) and open_preview(rec):
        rec.mark("preview")
        rec.wait(3)
        shot("demo_research_preview")
        tour(rec, steps=4)
        close_preview()
    rec.mark("scheduled tasks")
    launch("automation/tasks", wait=1)
    rec.wait(5)
    shot("demo_research_tasks")
    log("scheduled tasks: " + json.dumps(e2e.state().get("scheduledTasks", []))[:600])
    to_root()
    finish(rec)


# ------------------------------------------------------------------ set-up


def setup():
    e2e.capture.require_installed()
    for cmd in (
        f"pm grant {PKG} android.permission.POST_NOTIFICATIONS",
        f"appops set {PKG} SYSTEM_ALERT_WINDOW ignore",
        f"cmd notification allow_dnd {PKG}",
        f"pm grant {PKG} android.permission.READ_SMS",
        f"pm grant {PKG} android.permission.READ_CALENDAR",
        f"pm grant {PKG} android.permission.WRITE_CALENDAR",
        f"pm grant {PKG} android.permission.READ_CONTACTS",
        f"cmd notification allow_listener {PKG}/atom.context.notifications.AtomNotificationListener",
        f"settings put secure enabled_accessibility_services {PKG}/atom.device.AtomAccessibilityService",
        "settings put secure accessibility_enabled 1",
        "settings put system screen_off_timeout 1800000",
        "settings put global window_animation_scale 1",
        "svc power stayon true",
        "cmd uimode night yes",
        "cmd notification set_dnd off",
    ):
        out = sh(cmd).strip()
        log(f"$ {cmd}" + (f" -> {out[:200]}" if out else ""))
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    launch(wait=6)
    extra = {"max_tokens": MAX_OUT}
    if MODEL != "deepseek-chat":
        extra["thinking"] = {"type": "disabled"}
    options = {
        "name": "DeepSeek",
        "base_url": e2e.APP_BASE_URL,
        "model": MODEL,
        "context_window": CTX,
        "titles": True,
        "extra_body": extra,
    }
    log("configure: " + e2e.call("configure", json.dumps(options, separators=(",", ":"))))
    e2e.ensure_accessibility()
    launch(wait=4)


def smoke_test():
    """One short request through the app before anything is recorded: a wrong key or model fails here, clearly."""
    w = e2e.Watch(e2e.run_ids())
    e2e.send_prompt("Reply with just the word OK.")
    run = w.wait(180, "smoke")
    ans = e2e.answer(run) if run else ""
    log(f"smoke test: answer={ans[:80]!r} error={run.get('error') if run else 'no run'}")
    e2e.call("clear")
    return bool(ans)


CLIPS = {"website": clip_website, "game": clip_game, "bill": clip_bill, "multiapp": clip_multiapp, "research": clip_research}


def main():
    os.makedirs(OUT, exist_ok=True)
    setup()
    if not smoke_test():
        print("::error::The app's first request to DeepSeek did not get an answer; see the relay log.")
        sys.exit(1)
    order = sys.argv[1:] or os.environ.get("DEMO_CLIPS", "website game bill multiapp research").split()
    for key in order:
        try:
            CLIPS[key]()
        except Exception as e:  # one failed clip must not lose the others
            log(f"{key} failed: {type(e).__name__}: {e}")
            sh("pkill -INT screenrecord")
        # Never start the next clip over a run that is still going.
        for _ in range(40):
            if not any(r.get("active") for r in e2e.state().get("runs", [])):
                break
            time.sleep(3)
        else:
            log("a run is still active; stopping all runs")
            e2e.call("stop")
            time.sleep(10)
        e2e.call("clear")
    print("=====CRASH BUFFER=====")
    print(e2e.crash_log() or "(clean)")
    print("=====END=====")


if __name__ == "__main__":
    main()
