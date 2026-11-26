#!/usr/bin/env python3
"""Cold-start check on the CI emulator: the app must open its first screen within the normal time.

Runs only on GitHub's disposable runners (.github/workflows/screenshots.yml). Each run force-stops the app, goes
home and times `am start -W` (the system's own launch timer, TotalTime). The first call (label "baseline", before
the on-device AI pack is installed) records the normal time; later calls (e.g. "pack-installed") must stay within
max(LIMIT_MS, 3 x baseline). A launch that is too slow or never completes prints the app's main-thread stack, its
recent log lines and the crash buffer, then fails the step.

Usage: startup_check.py <label> [runs]
"""

import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from capture import ACTIVITY, OUT, PKG, adb, sh, shot  # noqa: E402

LIMIT_MS = int(os.environ.get("STARTUP_LIMIT_MS", "20000"))
BASELINE = os.path.join(OUT, "startup_baseline.json")


def cold_start():
    sh(f"am force-stop {PKG}")
    sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    t0 = time.time()
    try:
        r = subprocess.run(["adb", "shell", "am", "start", "-W", "-n", ACTIVITY], capture_output=True, timeout=120)
        out = (r.stdout + r.stderr).decode(errors="replace")
    except subprocess.TimeoutExpired:
        return None, time.time() - t0, "am start -W did not return within 120 s"
    m = re.search(r"TotalTime: (\d+)", out)
    return (int(m.group(1)) if m else None), time.time() - t0, out.strip()


def diagnose(label):
    print(f"=====STARTUP DIAGNOSIS {label}=====")
    pid = sh(f"pidof {PKG}").strip()
    print(f"pid: {pid or '(not running)'}")
    print(sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4"))
    print(sh(f"dumpsys activity processes | grep -A3 -E 'ProcessRecord.*{re.escape(PKG)}' | head -20"))
    if pid:
        # Java stacks of every thread; needs a root adbd, which the emulator image allows.
        adb("root", timeout=60)
        adb("wait-for-device", timeout=120)
        time.sleep(3)
        stacks = sh(f"debuggerd -j {pid}", timeout=120)
        blocks = stacks.split("\n\n")
        main = [b for b in blocks if b.startswith('"main"')]
        print("--- main thread ---")
        print((main[0] if main else stacks[:6000])[:6000])
        busy = [b for b in blocks if "Runnable" in b.splitlines()[0] or "Blocked" in b.splitlines()[0]] if blocks else []
        print(f"--- runnable/blocked threads ({len(busy)}) ---")
        for b in busy[:12]:
            print(b[:1500])
            print()
        print("--- app log (last 250 lines) ---")
        print("\n".join(adb("logcat", "-d", "--pid", pid, timeout=60).decode(errors="replace").splitlines()[-250:]))
    print("--- crash buffer ---")
    print(adb("logcat", "-d", "-b", "crash", timeout=60).decode(errors="replace")[-6000:] or "(empty)")
    print("--- system errors (last 80) ---")
    print("\n".join(adb("logcat", "-d", "*:E", timeout=60).decode(errors="replace").splitlines()[-80:]))
    print("=====END=====", flush=True)
    try:
        shot(f"startup_{label}")
    except Exception as e:
        print(f"screenshot failed: {e}")


def main():
    label = sys.argv[1]
    runs = int(sys.argv[2]) if len(sys.argv) > 2 else 2
    baseline = None
    if os.path.exists(BASELINE):
        baseline = json.load(open(BASELINE)).get("ms")
    limit = max(LIMIT_MS, 3 * baseline) if baseline else LIMIT_MS
    times, bad = [], False
    for i in range(runs):
        ms, wall, detail = cold_start()
        times.append(ms)
        ok = ms is not None and ms <= limit
        print(f"[startup] {label} cold start {i + 1}/{runs}: TotalTime={ms} ms, wall {wall:.1f} s, limit {limit} ms -> {'ok' if ok else 'TOO SLOW'}")
        if not ok:
            print(detail[-1500:])
            diagnose(f"{label}_{i + 1}")
            bad = True
        time.sleep(5)
    good = [t for t in times if t is not None]
    if label == "baseline" and good:
        os.makedirs(OUT, exist_ok=True)
        json.dump({"ms": max(good)}, open(BASELINE, "w"))
    print(f"::notice title=Cold start ({label})::{times} ms (limit {limit} ms)")
    sys.stdout.flush()
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    main()
