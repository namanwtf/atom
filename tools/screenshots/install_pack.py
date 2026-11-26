#!/usr/bin/env python3
"""Install a locally built on-device AI pack into the debug app on a CI emulator, and prove it loads.

Runs only on GitHub's disposable runners. The pack APK is pushed to /data/local/tmp, copied into the debug app's
cache with `run-as` (debug builds are debuggable), and handed to the app's debug-only DebugPackReceiver, which
installs it like a download (minus the SHA-256 step: a local build has no published checksum; package, version
and signing key are still checked) and loads each native library once. The log lines (tag AtomPack) and any crash
are printed, so the job log shows whether dynamic loading works.

Usage: install_pack.py <path to ondevice-pack-debug.apk>
"""

import os
import subprocess
import sys
import time

PKG = os.environ.get("ATOM_PKG", "io.github.namanwtf.atom.debug")
ACTIVITY = f"{PKG}/atom.app.ui.MainActivity"
RECEIVER = f"{PKG}/atom.app.ondevice.DebugPackReceiver"
TMP = "/data/local/tmp/atom-ondevice-debug.apk"
TARGET = "cache/atom4_debug_pack.apk"


def run(*args, timeout=120):
    r = subprocess.run(list(args), capture_output=True, timeout=timeout)
    out = (r.stdout + r.stderr).decode(errors="replace")
    print(f"[pack] $ {' '.join(args)} -> {r.returncode}\n{out.strip()[:2000]}")
    return r.returncode, out


def main():
    apk = sys.argv[1]
    if not os.path.isfile(apk):
        print(f"[pack] FATAL: {apk} not found", file=sys.stderr)
        sys.exit(1)
    print(f"[pack] pack APK: {apk} ({os.path.getsize(apk) // 1024} KB)")
    run("adb", "logcat", "-c")
    # Start the app once so its cache folder exists and it is not in the "stopped" state for the broadcast.
    run("adb", "shell", "am", "start", "-W", "-n", ACTIVITY)
    time.sleep(4)
    code, _ = run("adb", "push", apk, TMP)
    if code != 0:
        sys.exit(1)
    run("adb", "shell", f"run-as {PKG} mkdir -p cache")
    code, _ = run("adb", "shell", f"cat {TMP} | run-as {PKG} sh -c 'cat > {TARGET}'")
    code, out = run("adb", "shell", f"run-as {PKG} ls -l {TARGET}")
    if code != 0 or "No such file" in out:
        print("[pack] FATAL: could not copy the pack into the app", file=sys.stderr)
        sys.exit(1)
    run("adb", "shell", "am", "broadcast", "-n", RECEIVER, "-a", "atom.app.debug.INSTALL_PACK", "--ez", "probe", "true")
    # Installing reads and loads the pack, then each native library is loaded once.
    deadline = time.time() + 90
    log = ""
    while time.time() < deadline:
        time.sleep(3)
        _, log = run("adb", "logcat", "-d", "-s", "AtomPack:*")
        if "probe:" in log and log.count("probe:") >= 3:
            break
        if "install failed" in log or "no pack at" in log:
            break
    print("=====PACK LOG=====")
    print(log)
    print("=====END=====")
    _, crash = run("adb", "logcat", "-d", "-b", "crash")
    print("=====CRASH BUFFER=====")
    print(crash[-6000:])
    print("=====END=====")
    _, links = run("adb", "logcat", "-d")
    print("=====LINKER AND CLASS LOADING=====")
    for line in links.splitlines():
        if any(k in line for k in ("UnsatisfiedLinkError", "dlopen", "ClassNotFoundException", "NoClassDefFoundError",
                                   "nativeloader", "AtomPack", "FATAL EXCEPTION")):
            print(line[:400])
    print("=====END=====")
    if "on-device AI pack ready" not in log:
        print("[pack] FATAL: the pack did not reach Ready", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
