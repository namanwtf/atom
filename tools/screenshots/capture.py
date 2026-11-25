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


def switch_checked(label):
    """True/False for the switch on the settings row titled `label` (the last such title), None if not found."""
    for _ in range(3):
        sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
        raw = adb("exec-out", "cat", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(raw)
            break
        except ET.ParseError:
            time.sleep(1)
    else:
        return None
    rows, switches = [], []
    for n in root.iter("node"):
        b = [int(x) for x in re.findall(r"\d+", n.get("bounds", ""))]
        if len(b) != 4:
            continue
        if n.get("text", "") == label:
            rows.append(b)
        if n.get("checkable") == "true":
            switches.append((b, n.get("checked") == "true"))
    if not rows:
        return None
    row = rows[-1]
    cy = (row[1] + row[3]) // 2
    best = None
    for b, checked in switches:
        d = abs((b[1] + b[3]) // 2 - cy)
        if d < 160 and (best is None or d < best[0]):
            best = (d, checked)
    return None if best is None else best[1]


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
        if PKG in block or "AtomBubble" in block:
            head = block.splitlines()[0] if block.splitlines() else ""
            frame = re.search(r"mFrame=\S+|frame=\[[^\]]+\]\[[^\]]+\]", block)
            attrs = re.search(r"mAttrs=\{[^\n]*", block)
            print("  ", head.strip()[:120], frame.group(0) if frame else "", (attrs.group(0)[:200] if attrs else ""))
    print("=====END=====")


def focus_report():
    """Which window has input focus and whether the keyboard is up (keyboard-fit evidence)."""
    win = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedWindow|imeInputTarget|imeLayeringTarget' | head -6")
    ime = sh("dumpsys input_method | grep -E 'mInputShown|mServedView|isInputViewShown|mImeWindowVis' | head -6")
    print("=====FOCUS=====")
    for line in (win + ime).splitlines():
        print("  " + line.strip()[:200])
    print("=====END=====")


FRAME = re.compile(r"(?:mFrame|\bframe)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def bubble_bounds():
    """Frame of the bubble window. It is titled "AtomBubble" (status, menu and drag layers have longer titles)."""
    out = sh("dumpsys window windows")
    blocks = out.split("Window #")
    for block in blocks:
        head = block.splitlines()[0] if block.splitlines() else ""
        if re.search(r"\bAtomBubble\}", head):
            m = FRAME.search(block)
            if m:
                return tuple(map(int, m.groups()))
    # Older builds: any small app-overlay window of the package.
    for block in blocks:
        if PKG in block and ("TYPE_APPLICATION_OVERLAY" in block or "ty=APPLICATION_OVERLAY" in block):
            m = FRAME.search(block)
            if m:
                l, t, r, b = map(int, m.groups())
                if (r - l) < 600 and (b - t) < 600:
                    return (l, t, r, b)
    print("[capture] overlay windows of the package, for diagnosis:")
    for block in blocks:
        if PKG in block or "AtomBubble" in block:
            lines = block.splitlines()
            print("   ", (lines[0] if lines else "")[:160])
            for line in lines:
                if "ty=" in line or "frame=" in line or "mFrame=" in line:
                    print("       ", line.strip()[:200])
    return None


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "dark"
    sh(f"cmd uimode night {'yes' if mode == 'dark' else 'no'}")
    # The bubble is on by default; keep it off the app screenshots until the bubble section below allows it.
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW ignore")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    sh(f"pm grant {PKG} android.permission.RECORD_AUDIO")
    sh("settings put global window_animation_scale 0.5")
    # The emulator has a hardware keyboard; show the on-screen one anyway so keyboard layouts can be checked.
    sh("settings put secure show_ime_with_hard_keyboard 1")
    p = f"{mode}_"

    launch(wait=8)
    shot(p + "01_launch")
    # Get past a first-run screen if this build still has one.
    for label in ("Skip", "Not now", "Later", "Continue", "Get started", "Done", "Finish"):
        if find(f"^{label}$"):
            tap(f"^{label}$")
    shot(p + "02_home")

    # Conversation drawer.
    if tap("conversations|drawer|menu|sidebar", wait=1.5):
        shot(p + "03_drawer")
        back()

    # Overflow menu.
    if tap("more|overflow|options", wait=1.2):
        shot(p + "04_overflow")
        back()

    # Model picker sheet from the model capsule.
    if tap("model|DeepSeek|choose", wait=1.5):
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

    # Floating bubble. It is on by default and shows once "display over other apps" is allowed.
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    launch("overlay/settings", wait=3)
    state = switch_checked("Floating bubble")
    print(f"[capture] Floating bubble switch checked: {state}")
    if state is False:
        b = None
        for text, desc, bounds in dump():
            if text == "Floating bubble" and bounds:
                b = bounds
        if b:
            tap_bounds(b)
            time.sleep(2)
        print(f"[capture] after tap, switch checked: {switch_checked('Floating bubble')}")
    shot(p + "30_bubble_switch")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    window_frames()
    shot(p + "31_bubble_idle")
    b = bubble_bounds()
    if not b:
        print("[capture] bubble window not found")
        return
    print(f"[capture] bubble frame at rest: {b}")
    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    # Long-press: the hold menu. Capture while still holding; lifting on the bubble selects nothing.
    subprocess.Popen(["adb", "shell", f"input swipe {cx} {cy} {cx} {cy} 4000"])
    time.sleep(2.2)
    window_frames()
    shot(p + "32_hold_menu")
    time.sleep(3)
    # A tap on the bubble spot (inside the menu window, away from the buttons) closes the menu.
    sh(f"input tap {cx} {cy}")
    time.sleep(1.2)
    # Mid-drag towards the dismiss target (stopping short of its 96dp hit radius).
    subprocess.Popen(["adb", "shell", f"input swipe {cx} {cy} 380 1750 5000"])
    time.sleep(4)
    shot(p + "33_drag_dismiss")
    time.sleep(3)
    window_frames()
    shot(p + "34_after_drag")
    b = bubble_bounds() or b
    print(f"[capture] bubble frame after the drag: {b}")
    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    # The hold menu again, now that the bubble rests on the other edge (the fan mirrors).
    subprocess.Popen(["adb", "shell", f"input swipe {cx} {cy} {cx} {cy} 3000"])
    time.sleep(2.2)
    shot(p + "36_hold_menu_other_side")
    time.sleep(2)
    sh(f"input tap {cx} {cy}")
    time.sleep(1.2)
    before = bubble_bounds()
    # Tap: the mini chat.
    sh(f"input tap {cx} {cy}")
    time.sleep(2.5)
    shot(p + "35_mini_chat")
    # Focus the composer: the panel shrinks so the composer sits on the keyboard.
    if tap("^Ask Atom…$", wait=2.5):
        focus_report()
        shot(p + "37_mini_chat_keyboard")
        back(wait=1.5)
    back(wait=1.5)
    focus_report()
    if find("^Ask Atom anything$|^Ask Atom"):
        print("[capture] the panel is still open after Back; closing it with its X")
        tap("^Close$", wait=1.5)
    window_frames()
    shot(p + "38_after_panel")
    after = bubble_bounds()
    print(f"[capture] bubble frame before the panel: {before}, after closing it: {after}, unchanged: {before == after}")
    # Drop on the X target: the bubble is fused with it (brace), and letting go hides it and turns the switch off.
    b = after or b
    cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    subprocess.Popen(["adb", "shell", f"input swipe {cx} {cy} 540 2010 4000"])
    time.sleep(3.6)
    shot(p + "39_drag_fused")
    time.sleep(2.5)
    window_frames()
    print(f"[capture] bubble after dropping on the target: {bubble_bounds()}")
    launch("overlay/settings", wait=3)
    print(f"[capture] Floating bubble switch after the drop: {switch_checked('Floating bubble')}")
    shot(p + "40_switch_after_drop")


if __name__ == "__main__":
    main()
