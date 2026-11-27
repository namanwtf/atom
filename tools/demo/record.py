#!/usr/bin/env python3
"""Screen recordings of real Atom runs for the README, made on a CI emulator (see .github/workflows/demo.yml).

Nothing is staged: each clip is the debug build doing a real task, sent in exactly like a voice command, against a
model served on the runner by llama.cpp (no API key). The recordings are printed into the job log as base64
between =====SHOT <name>.mp4===== and =====END===== markers, like the screenshots.

Usage: python3 tools/demo/record.py
"""

import base64
import os
import shlex
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "e2e"))
os.environ.setdefault("SHOT_DIR", "demo-out")

import run as e2e  # noqa: E402  (shared app hook, prompt and polling helpers)
from capture import PKG, sh, shot, launch, find, tap, tap_bounds  # noqa: E402

OUT = os.environ["SHOT_DIR"]

CLIPS = [
    ("dnd", "Turn on Do Not Disturb.", True),
    ("reminder", "Every day at 9am remind me to drink water.", False),
    ("browser", "Use the agent browser to open https://example.com, read the page, and tell me in one sentence what it says.", False),
]


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
    configured = e2e.call("configure", __import__("json").dumps(options, separators=(",", ":")))
    e2e.log("configure: " + configured)
    launch(wait=4)


def warm_up():
    """The first request carries the whole system prompt; let the server cache it before anything is recorded."""
    known = e2e.run_ids()
    w = e2e.Watch(known)
    e2e.send_prompt("Reply with just the word OK.")
    w.wait(1500, "warmup")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)


class Recorder:
    """screenrecord stops after 180 s, so a long run is recorded as consecutive segments."""

    def __init__(self, name):
        self.name = name
        self.parts = []
        self.proc = None

    def _start(self):
        path = f"/sdcard/{self.name}_{len(self.parts)}.mp4"
        self.parts.append(path)
        self.proc = subprocess.Popen(
            ["adb", "shell", "screenrecord", "--size", "720x1600", "--bit-rate", "2000000", "--time-limit", "175", path]
        )

    def start(self):
        self._start()

    def tick(self):
        if self.proc and self.proc.poll() is not None:
            self._start()

    def stop(self):
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
            subprocess.run(["adb", "pull", p, dst], capture_output=True, timeout=120)
            if os.path.exists(dst) and os.path.getsize(dst) > 0:
                local.append(dst)
        if not local:
            return None
        lst = os.path.join(OUT, f"{self.name}.txt")
        with open(lst, "w") as f:
            for p in local:
                f.write(f"file '{os.path.abspath(p)}'\n")
        out = os.path.join(OUT, f"{self.name}.mp4")
        # Real time, 10 fps, small: the README GIF is cut and sped up from this, with the speed stated.
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst,
             "-vf", "fps=10,scale=540:-2", "-c:v", "libx264", "-crf", "30", "-preset", "veryslow",
             "-pix_fmt", "yuv420p", "-an", out],
            check=False,
        )
        return out if os.path.exists(out) else None


def print_file(path):
    data = open(path, "rb").read()
    print(f"=====SHOT {os.path.basename(path)}=====")
    enc = base64.b64encode(data).decode()
    for i in range(0, len(enc), 16000):
        print(enc[i : i + 16000])
    print("=====END=====")
    sys.stdout.flush()


def type_prompt(prompt):
    """Types the request into the composer and taps Send, as a person would. False if the composer was not found."""
    if not tap("^Ask Atom to do something", wait=1.5):
        return False
    sh("input text " + shlex.quote(prompt.replace(" ", "%s")))
    time.sleep(1.5)
    send = find("^Send$")
    if send is None:
        return False
    tap_bounds(send)
    return True


def record(name, prompt, show_shade):
    e2e.call("clear")
    launch(wait=4)
    known = e2e.run_ids()
    w = e2e.Watch(known)
    rec = Recorder(name)
    rec.start()
    time.sleep(2)
    t0 = time.time()
    typed = type_prompt(prompt)
    run = None
    if typed:
        for _ in range(4):
            time.sleep(e2e.POLL_S)
            run = w.poll()
            if run:
                break
    if not run:
        e2e.log(f"{name}: composer not used (typed={typed}); sending the prompt by intent")
        e2e.send_prompt(prompt)
    while time.time() - t0 < 900:
        rec.tick()
        run = w.poll()
        if run and not run["active"]:
            break
        time.sleep(e2e.POLL_S)
    e2e.log(f"{name}: {time.time() - t0:.0f}s, tools {e2e.names(run) if run else '-'}")
    if run:
        e2e.log(f"  answer: {e2e.answer(run)[:400]!r}")
    time.sleep(5)
    if show_shade:
        # The device's own quick settings, so the result is seen outside Atom too.
        sh("cmd statusbar expand-settings")
        time.sleep(5)
        sh("cmd statusbar collapse")
        time.sleep(2)
    rec.stop()
    shot(f"demo_{name}_end")
    out = rec.collect()
    if out:
        e2e.log(f"{name}: {os.path.getsize(out)} bytes")
        print_file(out)
    sh("cmd notification set_dnd off")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)


def terminal():
    """A screenshot of the terminal after a few ordinary commands in its Android shell tab."""
    launch("linux/terminal", wait=5)
    for cmd in ("uname -sr", "getprop ro.build.version.release", "echo $((6*7))", "ls / | head -n 6"):
        sh("input text " + shlex.quote(cmd.replace(" ", "%s")))
        sh("input keyevent KEYCODE_ENTER")
        time.sleep(1.5)
    shot("demo_terminal")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)


def main():
    os.makedirs(OUT, exist_ok=True)
    setup()
    try:
        terminal()
    except Exception as e:
        e2e.log(f"terminal failed: {type(e).__name__}: {e}")
    warm_up()
    for name, prompt, shade in CLIPS:
        try:
            record(name, prompt, shade)
        except Exception as e:  # one failed clip must not lose the others
            e2e.log(f"{name} failed: {type(e).__name__}: {e}")


if __name__ == "__main__":
    main()
