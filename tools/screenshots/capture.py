#!/usr/bin/env python3
"""Drive a CI emulator through Atom's main screens and print every screenshot into the job log.

Runs only on GitHub's disposable runners (never on a personal machine). Besides the uploaded artifact,
each screenshot is printed as base64 JPEG between markers, so a job log alone is enough;
tools/screenshots/extract.py turns a downloaded log back into image files.

Each shot also prints the visible texts from a UI hierarchy dump, so the screen's wording can be
checked without opening the image.
"""

import base64
import io
import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# Debug builds carry applicationIdSuffix ".debug" (app/build.gradle.kts).
PKG = os.environ.get("ATOM_PKG", "io.github.namanwtf.atom.debug")
ACTIVITY = f"{PKG}/atom.app.ui.MainActivity"
EXTRA_ROUTE = "atom.app.EXTRA_ROUTE"
EXTRA_DEMO = "atom.app.debug.EXTRA_DEMO_TRANSCRIPT"
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


def find_last(pattern):
    """Like find, but the last match in the hierarchy (a list row below a card with the same title)."""
    rx = re.compile(pattern, re.I)
    hit = None
    for text, desc, bounds in dump():
        if bounds and (rx.search(text or "") or rx.search(desc or "")):
            hit = bounds
    return hit


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
        # Half size in the log (the full-size PNG is in the artifact): a whole run then fits in what the log API
        # returns, which reads only the last few thousand lines.
        img = img.resize((img.width // 2, img.height // 2))
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
    for i in range(0, len(enc), 16000):
        print(enc[i : i + 16000])
    print("=====END=====")
    sys.stdout.flush()


def frames(name, count=5, gap=0.15):
    """Quick screenshots in a row, without the UI dump: a sequence through an animation (run it slowed down)."""
    for i in range(count):
        try:
            shot_only(f"{name}_{i + 1}")
        except Exception as e:  # one lost frame must not stop the pass
            print(f"[capture] frame {name}_{i + 1} failed: {e}")
        time.sleep(gap)


def shot_only(name):
    png = adb("exec-out", "screencap", "-p", timeout=60)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, f"{name}.png"), "wb") as f:
        f.write(png)
    from PIL import Image

    img = Image.open(io.BytesIO(png)).convert("RGB")
    img = img.resize((img.width // 3, img.height // 3))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=75)
    enc = base64.b64encode(buf.getvalue()).decode()
    print(f"=====SHOT {name}.jpg=====")
    for i in range(0, len(enc), 16000):
        print(enc[i : i + 16000])
    print("=====END=====")
    sys.stdout.flush()


def drawer_motion(p, label):
    """The drawer opening and closing, slowed 10x (animator duration scale), five frames each way."""
    sh("settings put global animator_duration_scale 10")
    try:
        time.sleep(2)  # a drawer closed just before may still be sliding out
        b = find("^Conversations$")
        if b is None:
            back()
            time.sleep(2)
            b = find("^Conversations$")
        if b is None:
            print("[capture] no drawer button for the motion frames")
            return
        tap_bounds(b)
        frames(p + label + "_opening")
        time.sleep(9)
        shot_only(p + label + "_open")
        # Close by tapping the dimmed chat to the right of the pane.
        sh("input tap 1030 1200")
        frames(p + label + "_closing")
        time.sleep(9)
    finally:
        sh("settings put global animator_duration_scale 1")
        time.sleep(1)


def fail(message):
    print(f"[capture] FATAL: {message}", file=sys.stderr)
    sys.stdout.flush()
    sys.exit(1)


def require_installed():
    """Stops the run when the package is missing, instead of photographing the launcher."""
    out = adb("shell", "pm", "path", PKG).decode(errors="replace")
    if "package:" not in out:
        fail(f"{PKG} is not installed (set ATOM_PKG if the debug id changed)")


def checked_sh(cmd):
    """Runs a setup command whose failure would make every screenshot wrong."""
    r = subprocess.run(["adb", "shell", cmd], capture_output=True, timeout=60)
    text = (r.stdout + r.stderr).decode(errors="replace")
    if r.returncode != 0 or re.search(r"(?i)\b(exception|error|unknown package)\b", text):
        fail(f"`{cmd}` failed: {text.strip()[:400]}")


def launch(route=None, wait=4):
    args = ["shell", "am", "start", "-W", "-n", ACTIVITY]
    if route:
        args += ["--es", EXTRA_ROUTE, route]
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=60)
    except subprocess.TimeoutExpired:
        # Print why the app did not come up (main-thread stack, log, crash buffer) before failing.
        from startup_check import diagnose

        diagnose("launch_timeout")
        fail(f"launch of {ACTIVITY} {route or ''} did not complete within 60 s")
    text = (r.stdout + r.stderr).decode(errors="replace")
    if r.returncode != 0 or "Error" in text:
        fail(f"launch of {ACTIVITY} {route or ''} failed: {text.strip()[:400]}")
    time.sleep(wait)


def demo(action, wait=4):
    """Debug builds only: opens ("open") or deletes ("clear") a long, clearly fake sample conversation.

    See app/src/debug/kotlin/atom/app/debug/DebugLaunch.kt; release builds ignore the extra. CI has no model key,
    so this is the only way to photograph a scrolled conversation.
    """
    r = subprocess.run(
        ["adb", "shell", "am", "start", "-W", "-n", ACTIVITY, "--es", EXTRA_DEMO, action],
        capture_output=True,
        timeout=60,
    )
    text = (r.stdout + r.stderr).decode(errors="replace")
    if r.returncode != 0 or "Error" in text:
        print(f"[capture] demo {action} failed: {text.strip()[:400]}")
        return False
    time.sleep(wait)
    return True


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


def crash_log():
    """Prints the crash buffer and the newest error lines, so a crash's stack trace lands in the job log."""
    print("=====CRASHLOG=====")
    crash = adb("logcat", "-d", "-b", "crash", timeout=60).decode(errors="replace")
    print(crash.strip() or "(crash buffer empty)")
    print("----- logcat *:E (last 200 lines) -----")
    errors = adb("logcat", "-d", "*:E", timeout=60).decode(errors="replace").splitlines()
    for line in errors[-200:]:
        print(line)
    print("=====END=====")
    sys.stdout.flush()


def type_into(label_pattern, text):
    """Taps the field labelled `label_pattern` and types `text` (spaces as %s, as `input text` needs)."""
    if not tap(label_pattern, wait=1.2):
        return False
    sh("input text " + text.replace(" ", "%s"))
    time.sleep(1)
    return True


def failed_run_report(p):
    """A sample failed run (debug only): its "Report a problem" action, then the report it opens."""
    # From a cold start, so the chat is the only screen and the sample opens in front.
    sh(f"am force-stop {PKG}")
    launch(wait=6)
    if not demo("failed"):
        return
    shot(p + "08_chat_failed_run")
    if tap("^Report a problem$", wait=4.0):
        shot(p + "08b_report_from_chat")
        if tap("^Screenshot$", wait=2.0):
            shot(p + "08c_report_with_screenshot")
        for i in range(1, 3):
            sh("input swipe 540 1800 540 500 300")
            time.sleep(1)
            shot(p + f"08d_report_preview_{i}")
        back()
    if tap("^More options$", wait=1.2):
        tap("^New conversation$", wait=1.5)
    demo("clear", wait=2)


def routines(p):
    """Ready-made routines: the empty list's card, a routine opened in the editor and saved, the list with it, Price
    watch's question, then the saved task deleted again so the next pass starts from an empty list."""
    launch("automation/tasks", wait=3)
    shot(p + "21b_routines_empty")
    if not tap("^Morning brief$", wait=3.0):
        return
    shot(p + "21c_routine_editor")
    sh("input swipe 540 1800 540 700 300")
    time.sleep(1)
    shot(p + "21c_routine_editor_scrolled")
    if not tap("^Save$", wait=3.0):
        back()
        return
    # A notification permission ask may follow the first save; the runner granted it, so usually nothing shows.
    shot(p + "21d_routines_list")
    if tap("^Price watch$", wait=2.0):
        shot(p + "21e_price_watch_ask")
        type_into("^Item, for example", "Example headphones")
        type_into("^Where: a shop name", "example.com")
        shot(p + "21f_price_watch_filled")
        if tap("^Continue$", wait=3.0):
            shot(p + "21g_price_watch_editor")
            back()
        else:
            back()
    # Delete the saved Morning brief (the row under the routine card of the same name).
    b = find_last("^Morning brief$")
    if b:
        tap_bounds(b)
        time.sleep(2.5)
        for _ in range(3):
            sh("input swipe 540 1800 540 500 300")
            time.sleep(0.8)
        if tap("^Delete scheduled task$", wait=1.5):
            tap("^Delete$", wait=2.0)
    shot(p + "21h_routines_after_delete")
    back()


def character_detail(p):
    """Opens the built-in character from the library: its detail screen once crashed the whole app."""
    try:
        launch("characters/library", wait=3)
        if tap("^Lumen$", wait=3.0):
            pid = sh(f"pidof {PKG}").strip()
            print(f"[capture] after opening Lumen the app pid is: {pid or '(not running: crashed)'}")
            shot(p + "22b_character_detail")
            back()
        else:
            shot(p + "22b_character_detail")
    finally:
        crash_log()


def pack_missing():
    """Before the on-device AI pack is installed: its download card, and "Hey Atom" explaining it needs the pack."""
    sh("cmd uimode night yes")
    checked_sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW ignore")
    sh(f"am force-stop {PKG}")
    launch(wait=6)
    for label in ("Skip", "Not now", "Later", "Continue", "Get started", "Done", "Finish"):
        if find(f"^{label}$"):
            tap(f"^{label}$")
    launch("models-on-device", wait=4)
    shot("pack_01_on_device_missing")
    back()
    launch("voice/settings", wait=3)
    for _ in range(3):
        sh("input swipe 540 1800 540 500 300")
        time.sleep(1)
    shot("pack_02_hey_atom_missing")
    back()


SITE_ID = "atom-demo"

# A small hand-written site for the preview shots (no model on the CI emulator). It follows the phone's dark or
# light mode, like the pages the agent is told to write.
SITE_PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Water tracker</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<main>
<h1>Water tracker</h1>
<p class="count">{count} of 8 glasses today</p>
<div class="glasses">{glasses}</div>
<button>Add a glass</button>
<p class="note">Debug sample site, written by the screenshot script.</p>
</main>
</body>
</html>
"""

SITE_CSS = """:root { color-scheme: light dark; --accent: #1e88e5; }
body { font-family: system-ui, sans-serif; margin: 0; padding: 24px; background: #f4f8fc; color: #12202e; }
@media (prefers-color-scheme: dark) { body { background: #0f1720; color: #e3edf7; } }
h1 { font-size: 28px; margin: 8px 0; }
.count { font-size: 20px; color: var(--accent); }
.glasses { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin: 20px 0; }
.glass { height: 64px; border-radius: 12px; border: 2px solid var(--accent); }
.glass.full { background: var(--accent); }
button { font-size: 18px; padding: 12px 20px; border-radius: 24px; border: 0; background: var(--accent); color: white; }
.note { opacity: .6; font-size: 13px; margin-top: 24px; }
"""


def site_page(count):
    glasses = "".join(f'<div class="glass{" full" if i < count else ""}"></div>' for i in range(8))
    return SITE_PAGE.replace("{count}", str(count)).replace("{glasses}", glasses)


def run_tool(name, args):
    """Debug builds only: runs one of the app's tools the way the agent would (E2eProvider's run_tool)."""
    spec = json.dumps({"name": name, "args": args}, separators=(",", ":"))
    out = sh(f"content call --uri content://{PKG}.e2e --method run_tool --arg {shlex.quote(spec)}", timeout=90).strip()
    m = re.search(r"result=(.*)\}\]\s*$", out, re.S)
    text = (m.group(1) if m else out).strip()
    print(f"[capture] {name}: {text[:300]}")
    return text


def site_preview(p):
    """The website feature: a site made through the agent's own tools, its live preview, and the reload after an edit."""
    run_tool("site_create", {"name": "Atom demo"})
    run_tool("site_write", {"site": SITE_ID, "path": "style.css", "content": SITE_CSS})
    run_tool("site_write", {"site": SITE_ID, "path": "index.html", "content": site_page(1)})
    launch(wait=3)
    # site_preview opens the preview itself only for a run the user sees in the foreground; this call comes from the
    # debug hook (no run), so the preview is opened by its route below, as a tap on the chat card would.
    result = run_tool("site_preview", {"name": SITE_ID})
    url = re.search(r"URL: (https://[a-z0-9-]+\.sites\.invalid/)", result)
    print(f"[capture] preview URL from site_preview: {url.group(1) if url else '(none)'}")
    time.sleep(5)
    if not find("^Close preview$"):
        print("[capture] opening the preview by its route")
        launch(f"web/sites/preview/{SITE_ID}", wait=5)
    time.sleep(2)
    shot(p + "16c_site_preview")
    print(f"[capture] preview shows the page: {find('1 of 8 glasses') is not None}")
    # Edit a file the way the agent does: the open preview reloads by itself.
    run_tool("site_write", {"site": SITE_ID, "path": "index.html", "content": site_page(5)})
    time.sleep(6)
    shot(p + "16d_site_preview_reloaded")
    print(f"[capture] reloaded preview shows the edit: {find('5 of 8 glasses') is not None}")
    back(wait=2)
    # The chat card a site_preview call leaves, and opening the preview from it.
    if demo("site", wait=5):
        shot(p + "16e_site_chat_card")
        if tap("^Open preview$", wait=5):
            shot(p + "16f_site_preview_from_card")
            back(wait=2)
        if tap("^More options$", wait=1.2):
            tap("^New conversation$", wait=1.5)
        demo("site_clear", wait=2)
    # Back to the first version, so the next pass shows the edit again.
    run_tool("site_write", {"site": SITE_ID, "path": "index.html", "content": site_page(1)})


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "dark"
    require_installed()
    if mode == "pack-missing":
        pack_missing()
        return
    sh(f"cmd uimode night {'yes' if mode == 'dark' else 'no'}")
    # The bubble is on by default; keep it off the app screenshots until the bubble section below allows it.
    checked_sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW ignore")
    checked_sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    checked_sh(f"pm grant {PKG} android.permission.RECORD_AUDIO")
    sh("settings put global window_animation_scale 0.5")
    # The emulator has a hardware keyboard; show the on-screen one anyway so keyboard layouts can be checked.
    sh("settings put secure show_ime_with_hard_keyboard 1")
    # Start each pass from a cold app, so the light pass does not resume on the dark pass's last screen.
    sh(f"am force-stop {PKG}")
    sh("input keyevent KEYCODE_HOME")
    p = f"{mode}_"

    launch(wait=8)
    shot(p + "01_launch")
    # Get past a first-run screen if this build still has one.
    for label in ("Skip", "Not now", "Later", "Continue", "Get started", "Done", "Finish"):
        if find(f"^{label}$"):
            tap(f"^{label}$")
    shot(p + "02_home")

    # Conversation drawer.
    # Exact labels first: the closed push drawer stays composed, so loose patterns can hit its own text.
    if tap("^Conversations$", wait=3.0) or tap("conversations|drawer|menu|sidebar", wait=3.0):
        shot(p + "03_drawer")
        back()
    drawer_motion(p, "03m_drawer")

    # Overflow menu.
    if tap("^More options$", wait=1.2) or tap("more|overflow|options", wait=1.2):
        shot(p + "04_overflow")
        back()

    # Model picker sheet from the model capsule. On a fresh install the capsule says "DeepSeek Flash · add key", and
    # one tap opens that provider's key screen instead; back returns to the chat.
    if tap("^Choose model$", wait=1.5):
        shot(p + "05_model_picker")
        back()
    elif tap("^Add the API key for ", wait=3.0):
        shot(p + "05_add_key")
        back()
        if not find("^Add the API key for "):
            print("[capture] back from the key screen did not return to the chat")

    # A long sample conversation: the frosted top bar and composer band over scrolled text, then the glass drawer
    # over it. Afterwards the chat goes back to a new conversation and the sample is deleted, so later shots and the
    # next pass start from the usual empty home.
    if demo("open"):
        if not find("^Demo transcript"):
            print("[capture] the sample conversation did not open")
        # Lands on the newest message; scroll a little towards older ones so text sits under both bars.
        sh("input swipe 540 1000 540 1350 700")
        time.sleep(2)
        shot(p + "06_chat_scrolled")
        if tap("^Conversations$", wait=3.0):
            shot(p + "07_drawer_over_chat")
            back()
        drawer_motion(p, "07m_drawer_over_chat")
        if tap("^More options$", wait=1.2):
            tap("^New conversation$", wait=1.5)
        demo("clear", wait=2)

    try:
        site_preview(p)
    except Exception as e:  # a failure here must not lose the other screenshots
        print(f"[capture] site preview pass failed: {e}")
    crash_log()

    routes = [
        ("10_settings", "settings"),
        ("11_models", "models"),
        # With the debug on-device AI pack installed (tools/screenshots/install_pack.py): "Ready" and its engines.
        ("11b_on_device", "models-on-device"),
        ("12_overlay_settings", "overlay/settings"),
        ("13_appearance", "general/appearance"),
        ("14_voice_settings", "voice/settings"),
        ("15_permissions", "device/permissions"),
        ("16_tools", "device/tools"),
        ("16b_sites", "web/sites"),
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
        ("27b_report", "report"),
        ("28_backup", "general/backup"),
        ("29_system", "vdisplay/system"),
    ]
    EXTRA_SCROLLS = {"10_settings": 4, "16_tools": 2, "15_permissions": 1, "27_about": 1, "27b_report": 2}
    only = os.environ.get("SHOT_ROUTES")
    for name, route in routes:
        if only and route not in only.split(","):
            continue
        launch(route, wait=3)
        shot(p + name)
        sh("input swipe 540 1800 540 500 300")
        time.sleep(1)
        shot(p + name + "_scrolled")
        # Long pages: keep scrolling so every group is on record.
        for i in range(2, EXTRA_SCROLLS.get(name, 0) + 2):
            sh("input swipe 540 1800 540 500 300")
            time.sleep(1)
            shot(p + name + f"_scrolled{i}")
        back()

    character_detail(p)
    if not only:
        failed_run_report(p)
        routines(p)

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
    # The placeholder is not in the accessibility tree; the field sits left of the mic ("Dictate") in the pill.
    mic = find("^Dictate$")
    if mic:
        sh(f"input tap {max(60, mic[0] - 300)} {(mic[1] + mic[3]) // 2}")
        time.sleep(2.5)
        focus_report()
        shot(p + "37_mini_chat_keyboard")
        # First Back closes only the keyboard: the panel must grow back to full height.
        back(wait=2.5)
        focus_report()
        shot(p + "37b_keyboard_closed")
    back(wait=1.5)
    focus_report()
    if find("^Ask Atom anything$|^Ask Atom"):
        print("[capture] the panel is still open after Back; closing it with its X")
        tap("^Close$", wait=1.5)
    window_frames()
    shot(p + "38_after_panel")
    after = bubble_bounds()
    print(f"[capture] bubble frame before the panel: {before}, after closing it: {after}, unchanged: {before == after}")
    # The same keyboard round with Atom's accessibility service on, so the measured keyboard top is used.
    sh("logcat -c")
    service = f"{PKG}/atom.device.AtomAccessibilityService"
    sh(f"settings put secure enabled_accessibility_services {service}")
    sh("settings put secure accessibility_enabled 1")
    time.sleep(4)
    print(f"[capture] accessibility services: {sh('settings get secure enabled_accessibility_services').strip()}")
    b2 = bubble_bounds() or after or b
    sh(f"input tap {(b2[0] + b2[2]) // 2} {(b2[1] + b2[3]) // 2}")
    time.sleep(2.5)
    mic = find("^Dictate$")
    if mic:
        sh(f"input tap {max(60, mic[0] - 300)} {(mic[1] + mic[3]) // 2}")
        time.sleep(2.5)
        focus_report()
        shot(p + "41_keyboard_measured")
        back(wait=2.5)
        shot(p + "42_keyboard_measured_closed")
    back(wait=1.5)
    if find("^Ask Atom anything$"):
        tap("^Close$", wait=1.5)
    print("=====LOGCAT AtomOverlay=====")
    print(sh("logcat -d -s AtomOverlay:I | tail -20"))
    print("=====END=====")
    sh("settings put secure enabled_accessibility_services ''")
    sh("settings put secure accessibility_enabled 0")
    time.sleep(2)
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
