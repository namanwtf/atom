#!/usr/bin/env python3
"""Screen recordings of real Atom runs for the launch posts, made on a CI emulator (.github/workflows/demo-recordings.yml).

Nothing is staged: each clip is the debug build doing a real task, typed into the app the way a person would, against
a model served on the runner by llama.cpp (no API key), or, in the offline clip, a model running inside the app.
Recordings are full resolution, printed into the job log as base64 between =====SHOT <name>.mp4===== and =====END=====.
Each clip also logs "[mark] <clip> t=<seconds> <event>" lines, timed from the start of its recording, so an editor
can label the parts that are sped up.

Usage: python3 tools/demo/record.py [clip ...]
"""

import base64
import json
import os
import shlex
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "e2e"))
sys.path.insert(0, os.path.join(HERE, "..", "screenshots"))
os.environ.setdefault("SHOT_DIR", "demo-out")

import run as e2e  # noqa: E402  (shared app hook, prompt and polling helpers)
from capture import PKG, sh, shot, launch, find, tap, tap_bounds, bubble_bounds, switch_checked, dump  # noqa: E402

OUT = os.environ["SHOT_DIR"]
W, H = 1080, 2400


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
            ["adb", "shell", "screenrecord", "--size", f"{W}x{H}", "--bit-rate", "8000000", "--time-limit", "170", path]
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
            time.sleep(min(1.0, max(0.05, end - time.time())))

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
        # Full width, 30 fps, real time. Speed-ups are done in editing, with the speed stated on screen.
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst,
             "-vf", "fps=30,scale=1080:-2:flags=lanczos", "-c:v", "libx264", "-crf", "26", "-preset", "slow",
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
        e2e.log(f"{rec.name}: {os.path.getsize(out)} bytes")
        print_file(out)
    else:
        e2e.log(f"{rec.name}: no recording")


def type_text(text):
    sh("input text " + shlex.quote(text.replace(" ", "%s")))


def type_prompt(rec, prompt):
    """Types the request into the composer and taps Send, as a person would. False if the composer was not found."""
    if not tap("^Ask Atom to do something", wait=1.2):
        return False
    type_text(prompt)
    rec.wait(1.5)
    send = find("^Send$")
    if send is None:
        return False
    tap_bounds(send)
    rec.mark("sent")
    return True


def wait_run(rec, w, timeout):
    """Polls the run, marking each tool call and the end. Returns the last run state."""
    t0 = time.time()
    seen = 0
    run = None
    phase = None
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
            if not run["active"]:
                rec.mark(f"done outcome={run.get('outcome')} engine={run.get('engine')}")
                break
        rec.wait(2)
    if run:
        e2e.log(f"{rec.name}: tools {e2e.names(run)} engine={run.get('engine')} outcome={run.get('outcome')}")
        e2e.log(f"  answer: {e2e.answer(run)[:500]!r}")
    return run


def setup():
    e2e.capture.require_installed()
    model_id, n_ctx, _ = e2e.server_info()
    e2e.log(f"model server: id={model_id} n_ctx={n_ctx}")
    for cmd in (
        f"pm grant {PKG} android.permission.POST_NOTIFICATIONS",
        f"appops set {PKG} SYSTEM_ALERT_WINDOW ignore",
        f"cmd notification allow_dnd {PKG}",
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
        e2e.log(f"$ {cmd}" + (f" -> {out[:200]}" if out else ""))
    sh("input keyevent KEYCODE_WAKEUP")
    sh("wm dismiss-keyguard")
    launch(wait=6)
    options = {
        "base_url": e2e.APP_BASE_URL,
        "model": model_id,
        "context_window": n_ctx,
        "titles": False,
        "extra_body": {"return_progress": True},
    }
    e2e.log("configure: " + e2e.call("configure", json.dumps(options, separators=(",", ":"))))
    launch(wait=4)
    # Linux downloads while the other clips record, as a user would have installed it.
    e2e.log("linux: " + e2e.call("linux_install", "Alpine"))


def warm_up():
    """The first request carries the whole system prompt; let the server cache it before anything is recorded."""
    w = e2e.Watch(e2e.run_ids())
    e2e.send_prompt("Reply with just the word OK.")
    w.wait(1500, "warmup")
    e2e.call("clear")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)


def new_chat():
    launch(wait=3)
    if tap("^More options$", wait=1.2):
        if not tap("^New conversation$", wait=1.5):
            sh("input keyevent KEYCODE_BACK")
    time.sleep(1)


def clip_task():
    """A device task typed into the app: Do Not Disturb and an alarm, then the phone's own UI showing both."""
    name = "task"
    new_chat()
    w = e2e.Watch(e2e.run_ids())
    rec = Recorder(name)
    rec.start()
    rec.wait(2)
    if not type_prompt(rec, "Turn on Do Not Disturb and set an alarm for 6:45"):
        e2e.log("task: composer not used; sending by intent")
        e2e.send_prompt("Turn on Do Not Disturb and set an alarm for 6:45")
        rec.mark("sent (intent)")
    wait_run(rec, w, 1200)
    rec.wait(5)
    shot("demo_task_chat")
    rec.mark("quick settings")
    sh("cmd statusbar expand-settings")
    rec.wait(5)
    shot("demo_task_qs")
    sh("cmd statusbar collapse")
    rec.wait(1.5)
    rec.mark("alarms")
    sh("am start -a android.intent.action.SHOW_ALARMS")
    rec.wait(5)
    shot("demo_task_alarms")
    sh("input keyevent KEYCODE_BACK")
    rec.wait(1)
    finish(rec)
    e2e.log("zen_mode: " + sh("settings get global zen_mode").strip())
    e2e.log("next alarm: " + sh("dumpsys alarm | grep -i -m3 'nextAlarmClock'").strip()[:300])
    sh("cmd notification set_dnd off")


def bubble_on():
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    launch("overlay/settings", wait=3)
    if switch_checked("Floating bubble") is False:
        tap("^Floating bubble$", wait=2)
    e2e.log(f"bubble switch: {switch_checked('Floating bubble')}")


def clip_bubble():
    """The floating bubble over another app: drag it, hold it for quick actions, tap it for the mini chat."""
    name = "bubble"
    bubble_on()
    sh("input keyevent KEYCODE_HOME")
    time.sleep(1)
    sh("am start -a android.settings.SETTINGS")
    time.sleep(3)
    b = bubble_bounds()
    if not b:
        e2e.log("bubble: window not found")
        return
    rec = Recorder(name)
    rec.start()
    rec.wait(2)
    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    tx = 120 if cx > W // 2 else W - 120
    rec.mark("drag")
    sh(f"input swipe {cx} {cy} {tx} {max(400, cy - 500)} 1400")
    rec.wait(2.5)
    b = bubble_bounds() or b
    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    rec.mark("drag back")
    sh(f"input swipe {cx} {cy} {W - tx} {min(1700, cy + 300)} 1400")
    rec.wait(2.5)
    b = bubble_bounds() or b
    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    rec.mark("hold")
    subprocess.Popen(["adb", "shell", f"input swipe {cx} {cy} {cx} {cy} 3500"])
    rec.wait(2.4)
    shot("demo_bubble_hold")
    rec.wait(1.8)
    sh(f"input tap {cx} {cy}")  # closes the hold menu without choosing
    rec.wait(1.5)
    rec.mark("tap: mini chat")
    sh(f"input tap {cx} {cy}")
    rec.wait(2.5)
    mic = find("^Dictate$")
    send = find("^Send$")
    anchor = mic or send
    if not anchor:
        e2e.log("bubble: mini chat not found")
        finish(rec)
        return
    sh(f"input tap {max(60, anchor[0] - 260)} {(anchor[1] + anchor[3]) // 2}")
    rec.wait(1.5)
    w = e2e.Watch(e2e.run_ids())
    type_text("Write a short text saying I will be 10 minutes late")
    rec.wait(1.5)
    send = find("^Send$")
    if send:
        tap_bounds(send)
        rec.mark("sent")
    wait_run(rec, w, 900)
    sh("input keyevent KEYCODE_BACK")  # the keyboard, if it is still up
    rec.wait(6)
    shot("demo_bubble_reply")
    tap("^Close$", wait=1.5)
    rec.wait(2)
    finish(rec)


def wait_linux(timeout):
    deadline = time.time() + timeout
    st = ""
    while time.time() < deadline:
        st = e2e.state().get("linux", "")
        if "Ready" in st or "Failed" in st:
            break
        time.sleep(10)
    e2e.log(f"linux: {st[:200]}")
    return "Ready" in st


def clip_terminal():
    """The terminal on the Alpine Linux environment: a package install and a Python one-liner."""
    name = "terminal"
    if not wait_linux(900):
        e2e.log("terminal: Linux is not ready; recording the Android shell only")
    launch("linux/terminal", wait=5)
    tab = find("^Alpine$") or find("^Debian$")
    if tab:
        tap_bounds(tab)
        time.sleep(4)
    rec = Recorder(name)
    rec.start()
    rec.wait(2)
    steps = [
        ("cat /etc/os-release | head -n 2", 4),
        ("uname -m", 3),
        ("apk add -q python3 git", 240),
        ("python3 -c \"import platform; print('Python', platform.python_version(), 'on', platform.machine())\"", 25),
        ("git --version", 10),
    ]
    for cmd, pause in steps:
        rec.mark(f"cmd {cmd}")
        type_text(cmd)
        rec.wait(0.6)
        sh("input keyevent KEYCODE_ENTER")
        rec.wait(pause)
    shot("demo_terminal")
    finish(rec)


def clip_offline():
    """An on-device model: download it in the app, pick it, turn on airplane mode, ask something."""
    name = "offline"
    launch("models-on-device", wait=4)
    rec = Recorder(name)
    rec.start()
    rec.wait(3)
    shot("demo_offline_catalog")
    btn = find("^Download$")
    if not btn:
        sh("input swipe 540 1800 540 1000 400")
        rec.wait(1.5)
        btn = find("^Download$")
    if not btn:
        e2e.log("offline: no Download button")
        finish(rec)
        return
    tap_bounds(btn)
    rec.mark("download")
    t0 = time.time()
    use = None
    while time.time() - t0 < 900:
        rec.wait(5)
        use = find("^Use$")
        if use or find("^In use$"):
            break
    e2e.log(f"offline: download took {time.time() - t0:.0f}s, use={use}")
    if use:
        tap_bounds(use)
        rec.mark("use")
        rec.wait(3)
    shot("demo_offline_in_use")
    new_chat()
    rec.mark("home")
    rec.wait(2)
    if tap("^Choose model$", wait=2.5):
        rec.mark("picker")
        shot("demo_offline_picker")
        rec.wait(3)
        sh("input keyevent KEYCODE_BACK")
        rec.wait(1.5)
    sh("cmd connectivity airplane-mode enable")
    rec.mark("airplane mode on")
    rec.wait(3)
    sh("cmd statusbar expand-settings")
    rec.wait(3)
    shot("demo_offline_airplane")
    sh("cmd statusbar collapse")
    rec.wait(1.5)
    w = e2e.Watch(e2e.run_ids())
    if not type_prompt(rec, "In two sentences, why is the sky blue?"):
        e2e.send_prompt("In two sentences, why is the sky blue?")
        rec.mark("sent (intent)")
    run = wait_run(rec, w, 1200)
    rec.wait(5)
    shot("demo_offline_answer")
    finish(rec)
    sh("cmd connectivity airplane-mode disable")
    e2e.log(f"offline engine: {run.get('engine') if run else None}")


def clip_menu():
    """The home screen, the frosted side menu over real conversations, and the model picker."""
    name = "menu"
    new_chat()
    rec = Recorder(name)
    rec.start()
    rec.wait(3)
    if tap("^Conversations$", wait=0.1):
        rec.mark("drawer")
        rec.wait(3.5)
        shot("demo_menu_drawer")
        sh("input tap 1030 1200")
        rec.wait(2)
    if tap("^Choose model$", wait=0.1):
        rec.mark("picker")
        rec.wait(3.5)
        shot("demo_menu_picker")
        sh("input keyevent KEYCODE_BACK")
        rec.wait(1.5)
    # The newest real conversation, then the drawer over it.
    if tap("^Conversations$", wait=2.5):
        rows = [n for n in dump() if n[2] and n[0] and 300 < n[2][1] < 1900 and n[2][0] < 700]
        e2e.log("drawer texts: " + " | ".join(n[0] for n in rows)[:600])
        conv = next((n for n in rows if "Do Not Disturb" in n[0] or "alarm" in n[0].lower()), None)
        if conv:
            tap_bounds(conv[2])
            rec.mark("conversation")
            rec.wait(3)
            sh("input swipe 540 900 540 1500 600")
            rec.wait(2)
            if tap("^Conversations$", wait=0.1):
                rec.mark("drawer over chat")
                rec.wait(3.5)
                shot("demo_menu_drawer_over_chat")
        sh("input tap 1030 1200")
        rec.wait(1.5)
    finish(rec)


CLIPS = {"task": clip_task, "bubble": clip_bubble, "menu": clip_menu, "terminal": clip_terminal, "offline": clip_offline}


def main():
    os.makedirs(OUT, exist_ok=True)
    setup()
    warm_up()
    order = sys.argv[1:] or ["task", "bubble", "menu", "terminal", "offline"]
    for key in order:
        try:
            CLIPS[key]()
        except Exception as e:  # one failed clip must not lose the others
            e2e.log(f"{key} failed: {type(e).__name__}: {e}")
            sh("pkill -INT screenrecord")
    print("=====CRASH BUFFER=====")
    print(e2e.crash_log() or "(clean)")
    print("=====END=====")


if __name__ == "__main__":
    main()
