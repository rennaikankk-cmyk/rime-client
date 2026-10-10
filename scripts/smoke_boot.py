#!/usr/bin/env python3
"""Launch 1.21.11 with rime. Window only — no quickPlay, no auto-join."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(r"E:\Dopamine 0.0.8\.minecraft")
VER = "1.21.11"
VER_DIR = ROOT / "versions" / VER
JSON_PATH = VER_DIR / f"{VER}.json"
JAVA = Path(r"C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\java.exe")
NATIVES = VER_DIR / f"{VER}-natives"
GAME_DIR = VER_DIR
ASSETS = ROOT / "assets"
LOG = VER_DIR / "logs" / "latest.log"
WAIT_BOOT_S = int(os.environ.get("RIME_SMOKE_BOOT", "120"))
WAIT_TITLE_S = int(os.environ.get("RIME_SMOKE_TITLE", "20"))

CRASH_PAT = re.compile(
    r"(Unreported exception|Reported exception|EXCEPTION_ACCESS_VIOLATION|hs_err_pid|"
    r"---- Minecraft Crash Report ----|Game crashed!|"
    r"Exception in thread \"Render thread\")",
    re.I,
)
OK_RIME = re.compile(r"Initialized .+ in \d+ms", re.I)
OK_MOD = re.compile(r"(rime-1\.2\.16|guizhouware|Loading Rime|Initializing Rime)", re.I)


def kill_instance() -> None:
    subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-Command",
            (
                "Get-CimInstance Win32_Process | Where-Object {"
                " $_.Name -match 'java' -and $_.CommandLine -and $_.CommandLine -like '*1.21.11*'"
                "} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
            ),
        ],
        check=False,
    )
    time.sleep(1)


def allowed(rules: list | None) -> bool:
    if not rules:
        return True
    allow = False
    for r in rules:
        action = r.get("action") == "allow"
        osr = r.get("os")
        if osr:
            name = osr.get("name")
            if name and name != "windows":
                continue
            arch = osr.get("arch")
            if arch == "x86" and sys.maxsize > 2**32:
                continue
        if r.get("features"):
            continue
        allow = action
    return allow


def lib_path(lib: dict) -> Path | None:
    downloads = lib.get("downloads", {})
    art = downloads.get("artifact")
    if art and "path" in art:
        return ROOT / "libraries" / art["path"]
    name = lib["name"]
    parts = name.split(":")
    g, a, v = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else None
    fname = f"{a}-{v}.jar" if not classifier else f"{a}-{v}-{classifier}.jar"
    return ROOT / "libraries" / Path(*g.split(".")) / a / v / fname


def build_classpath(j: dict) -> list[Path]:
    cps: list[Path] = []
    for lib in j.get("libraries", []):
        if not allowed(lib.get("rules")):
            continue
        downloads = lib.get("downloads", {})
        if "artifact" not in downloads and "natives" in lib:
            continue
        name = lib["name"]
        if any(x in name for x in ("natives-linux", "natives-macos", "natives-osx", "linux-", "macos")):
            if "natives-windows" not in name:
                continue
        p = lib_path(lib)
        if p is None or not p.exists():
            continue
        cps.append(p)
    cps.append(VER_DIR / f"{VER}.jar")
    return cps


def process_responding(pid: int) -> bool:
    r = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-Command",
            f"(Get-Process -Id {pid} -ErrorAction SilentlyContinue).Responding",
        ],
        capture_output=True,
        text=True,
    )
    out = (r.stdout or "").strip()
    if not out:
        return False
    return out == "True"


def launch() -> int:
    j = json.loads(JSON_PATH.read_text(encoding="utf-8"))
    cps = build_classpath(j)
    cp = ";".join(str(p) for p in cps)
    natives = NATIVES if NATIVES.exists() else VER_DIR / "natives"
    main_class = j.get("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient")
    asset_index = j.get("assetIndex", {}).get("id", "29")
    env = os.environ.copy()
    env["APPDATA"] = str(ROOT.parent)
    out = VER_DIR / "logs" / "smoke-launch-stdout.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    if LOG.exists():
        LOG.write_text("", encoding="utf-8")
    args = [
        str(JAVA),
        "-XX:+UseG1GC",
        "-XX:-OmitStackTraceInFastThrow",
        "-Dlog4j2.formatMsgNoLookups=true",
        f"-Djava.library.path={natives}",
        f"-Djna.tmpdir={natives}",
        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}",
        f"-Dio.netty.native.workdir={natives}",
        "-Dminecraft.launcher.brand=rime-smoke",
        "-Dminecraft.launcher.version=1",
        "-Xmn256m",
        "-Xmx4096m",
        "-cp",
        cp,
        main_class,
        "--username",
        "AcidPortTest",
        "--version",
        VER,
        "--gameDir",
        str(GAME_DIR),
        "--assetsDir",
        str(ASSETS),
        "--assetIndex",
        asset_index,
        "--uuid",
        "00000000000000000000000000000000",
        "--accessToken",
        "0",
        "--userType",
        "legacy",
        "--versionType",
        "rime-smoke",
        "--width",
        "854",
        "--height",
        "480",
    ]
    with open(out, "w", encoding="utf-8", errors="replace") as f:
        proc = subprocess.Popen(
            args,
            cwd=str(GAME_DIR),
            env=env,
            stdout=f,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS,
        )
    (VER_DIR / "logs" / "smoke-launch.pid").write_text(str(proc.pid), encoding="utf-8")
    print(f"[smoke] launched pid={proc.pid} window-only (no join) ver={VER}", flush=True)
    return proc.pid


def tail_log() -> str:
    parts: list[str] = []
    for p in (LOG, VER_DIR / "logs" / "smoke-launch-stdout.txt"):
        if not p.exists():
            continue
        try:
            parts.append(p.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            pass
    return "\n".join(parts)


def hold_ok(pid: int) -> int:
    hold_until = time.time() + WAIT_TITLE_S
    stuck = 0
    while time.time() < hold_until:
        text2 = tail_log()
        m2 = CRASH_PAT.search(text2)
        if m2:
            idx = m2.start()
            print("[smoke] FAIL crash during hold:")
            print(text2[max(0, idx - 200) : idx + 900])
            return 3
        if process_responding(pid):
            stuck = 0
        else:
            stuck += 1
            print(f"[smoke] warn Not Responding x{stuck}", flush=True)
            if stuck >= 8:
                print("[smoke] FAIL process Not Responding >=8s on home")
                return 7
        time.sleep(1)
    if not process_responding(pid):
        print("[smoke] FAIL process Not Responding at end of home hold")
        return 7
    print("[smoke] PASS window open on home, process responding (left running)")
    return 0


def main() -> int:
    rime = VER_DIR / "mods" / "rime-1.2.16.jar"
    if not rime.exists():
        print(f"[smoke] FAIL missing {rime}")
        return 1
    if not JAVA.exists():
        print(f"[smoke] FAIL missing java {JAVA}")
        return 1
    if not JSON_PATH.exists():
        print(f"[smoke] FAIL missing {JSON_PATH}")
        return 1
    kill_instance()
    pid = launch()
    t0 = time.time()
    booted = False
    last_len = 0
    while time.time() - t0 < WAIT_BOOT_S:
        text = tail_log()
        if len(text) != last_len:
            last_len = len(text)
            m = CRASH_PAT.search(text)
            if m:
                idx = m.start()
                print("[smoke] FAIL crash detected:")
                print(text[max(0, idx - 200) : idx + 900])
                return 2
            if not booted and OK_RIME.search(text) and OK_MOD.search(text):
                booted = True
                print(
                    f"[smoke] rime loaded @ {time.time()-t0:.0f}s — holding home {WAIT_TITLE_S}s",
                    flush=True,
                )
                return hold_ok(pid)
            if not booted and OK_RIME.search(text):
                booted = True
                print(
                    f"[smoke] client initialized @ {time.time()-t0:.0f}s — holding home {WAIT_TITLE_S}s",
                    flush=True,
                )
                return hold_ok(pid)
            if not booted and OK_MOD.search(text) and re.search(
                r"(Sound engine started|OpenAL|Backend texture|Reloading ResourceManager)", text, re.I
            ):
                booted = True
                print(
                    f"[smoke] client up + rime present @ {time.time()-t0:.0f}s — holding home {WAIT_TITLE_S}s",
                    flush=True,
                )
                return hold_ok(pid)
        try:
            os.kill(pid, 0)
        except OSError:
            text = tail_log()
            if CRASH_PAT.search(text):
                print("[smoke] FAIL process exited with crash in log")
                print(text[-2000:])
                return 4
            print("[smoke] FAIL process exited early")
            print(text[-1500:] if text else "(empty log)")
            return 5
        time.sleep(1.5)

    print(f"[smoke] FAIL timeout booted={booted}")
    print(tail_log()[-2500:] if tail_log() else "(empty log)")
    return 6


if __name__ == "__main__":
    raise SystemExit(main())
