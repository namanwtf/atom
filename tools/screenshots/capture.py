#!/usr/bin/env python3
"""Drive a CI emulator through Atom's main screens and print every screenshot into the job log.

Runs only on GitHub's disposable runners (never on a personal machine). Workflow artifacts are not
reachable from every review environment, so each screenshot is printed as base64 JPEG between
markers; tools/screenshots/extract.py turns a downloaded log back into image files.

Each shot also prints the visible texts from a UI hierarchy dump, which is the "window dump"
evidence PROJECT.md asks for.
"""

import base64
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = os.environ.get("ATOM_PKG", "io.github.namanwtf.atomnext")
ACTIVITY = f"{PKG}/atom.app.ui.MainActivity"
EXTRA_ROUTE = "atom.app.EXTRA_ROUTE"
OUT = os.environ.get("SHOT_DIR", "shots")


def adb(*args, check=False, timeout=60):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout).decode(errors="replace")


def dump():
    """Returns the current UI hierarchy as a list of (text, desc, bounds) tuples."""
    for _ in range(3):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        raw = adb("exec-out", "cat", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(raw)
            break
        except ET.ParseError:
            time.sleep(1)
    else:
        return []
    nodes = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        bounds = tuple(int(x) for x in b) if len(b) == 4 else None
        nodes.append((n.get("text", ""), n.get("content-desc", ""), bounds))
    return nodes


def find(pattern):
    rx = re.compile(pattern, re.I)
    for text, desc, bounds in dump():
        if bounds and (rx.search(text or "") or rx.search(desc or "")):
            return bounds
    return None


def tap_bounds(b):
    x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    sh(f"input tap {x} {y}")


def tap(pattern, wait=1.5):
    b = find(pattern)
    if b is None:
        print(f"[capture] no node matches /{pattern}/")
        return False
    tap_bounds(b)
    time.sleep(wait)
    return True


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    png = adb("exec-out", "screencap", "-p", timeout=60)
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        f.write(png)
    data = png
    try:
        from PIL import Image

        img = Image.open(io.BytesIO(png)).convert("RGB")
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=80)
        data = buf.getvalue()
        kind = "jpg"
    except Exception as e:  # Pillow missing: fall back to the PNG
        print(f"[capture] pillow unavailable ({e}); printing PNG")
        kind = "png"
    texts = [t or d for t, d, _ in dump() if (t or d)]
    print(f"=====TEXTS {name}=====")
    for t in texts:
        print("  " + t.replace("\n", " ")[:160])
    print(f"=====SHOT {name}.{kind}=====")
    enc = base64.b64encode(data).decode()
    for i in range(0, len(enc), 4000):
        print(enc[i : i + 4000])
    print("=====END=====")
    sys.stdout.flush()


def launch(route=None, wait=4):
    args = ["shell", "am", "start", "-W", "-n", ACTIVITY]
    if route:
        args += ["--es", EXTRA_ROUTE, route]
    adb(*args)
    time.sleep(wait)


def back(wait=1.2):
    sh("input keyevent KEYCODE_BACK")
    time.sleep(wait)


def window_frames():
    """Prints overlay window geometry (the bubble and its menus are separate windows)."""
    out = sh("dumpsys window windows")
    print("=====WINDOWS=====")
    for block in out.split("Window #"):
        if PKG in block:
            head = block.splitlines()[0] if block.splitlines() else ""
            frame = re.search(r"mFrame=\S+|frame=\[[^\]]+\]\[[^\]]+\]", block)
            attrs = re.search(r"mAttrs=\{[^\n]*", block)
            print("  ", head.strip()[:120], frame.group(0) if frame else "", (attrs.group(0)[:200] if attrs else ""))
    print("=====END=====")


def bubble_bounds():
    out = sh("dumpsys window windows")
    for block in out.split("Window #"):
        if PKG in block and ("TYPE_APPLICATION_OVERLAY" in block or "ty=APPLICATION_OVERLAY" in block):
            m = re.search(r"mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block) or re.search(
                r"frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block
            )
            if m:
                l, t, r, b = map(int, m.groups())
                if (r - l) < 600 and (b - t) < 600:
                    return (l, t, r, b)
    return None


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "dark"
    sh(f"cmd uimode night {'yes' if mode == 'dark' else 'no'}")
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    sh(f"pm grant {PKG} android.permission.RECORD_AUDIO")
    sh("settings put global window_animation_scale 0.5")
    p = f"{mode}_"

    # Start clean so each mode opens on the home chat, not on the screen the previous mode left open.
    sh(f"am force-stop {PKG}")
    launch(wait=8)
    shot(p + "01_launch")
    # Get past a first-run screen if this build still has one.
    for label in ("Skip", "Not now", "Later", "Continue", "Get started", "Done", "Finish"):
        if find(f"^{label}$"):
            tap(f"^{label}$")
    shot(p + "02_home")

    # Conversation drawer.
    # Exact labels first: the closed push drawer stays composed, so loose patterns can hit its own text.
    if tap("^Conversations$", wait=1.5) or tap("conversations|drawer|menu|sidebar", wait=1.5):
        shot(p + "03_drawer")
        back()

    # Overflow menu.
    if tap("^More options$", wait=1.2) or tap("more|overflow|options", wait=1.2):
        shot(p + "04_overflow")
        back()

    # Model picker sheet from the model capsule.
    if tap("^Choose model$", wait=1.5) or tap("model|DeepSeek|choose", wait=1.5):
        shot(p + "05_model_picker")
        back()

    routes = [
        ("10_settings", "settings"),
        ("11_models", "models"),
        ("12_overlay_settings", "overlay/settings"),
        ("13_appearance", "general/appearance"),
        ("14_voice_settings", "voice/settings"),
        ("15_permissions", "device/permissions"),
        ("16_tools", "device/tools"),
        ("17_memory", "extend/memory"),
        ("18_skills", "extend/skills"),
        ("19_mcp", "extend/mcp"),
        ("20_subagents", "extend/subagents"),
        ("21_scheduled", "automation/tasks"),
        ("22_characters", "characters/library"),
        ("23_linux", "linux/home"),
        ("24_terminal", "linux/terminal"),
        ("25_web_search", "web/search"),
        ("26_images", "web/images"),
        ("27_about", "general/about"),
        ("28_backup", "general/backup"),
        ("29_system", "vdisplay/system"),
    ]
    only = os.environ.get("SHOT_ROUTES")
    for name, route in routes:
        if only and route not in only.split(","):
            continue
        launch(route, wait=3)
        shot(p + name)
        sh("input swipe 540 1800 540 500 300")
        time.sleep(1)
        shot(p + name + "_scrolled")
        back()

    if mode != "dark":
        return

    # Floating bubble: switch it on from Settings -> Floating assistant, then look at it over the launcher.
    launch("overlay/settings", wait=3)
    tap("Floating bubble", wait=2)
    shot(p + "30_bubble_switch")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    window_frames()
    shot(p + "31_bubble_idle")
    b = bubble_bounds()
    if b:
        cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        # Long-press: the hold menu. Capture while still holding.
        subprocess.Popen(["adb", "shell", f"input swipe {cx} {cy} {cx} {cy} 4000"])
        time.sleep(2.2)
        shot(p + "32_hold_menu")
        time.sleep(3)
        sh("input keyevent KEYCODE_BACK")
        time.sleep(1)
        # Mid-drag towards the dismiss target.
        subprocess.Popen(["adb", "shell", f"input swipe {cx} {cy} 540 1750 5000"])
        time.sleep(4)
        shot(p + "33_drag_dismiss")
        time.sleep(3)
        window_frames()
        shot(p + "34_after_drag")
        b = bubble_bounds() or b
        cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        sh(f"input tap {cx} {cy}")
        time.sleep(2.5)
        shot(p + "35_mini_chat")
    else:
        print("[capture] bubble window not found")


if __name__ == "__main__":
    main()
