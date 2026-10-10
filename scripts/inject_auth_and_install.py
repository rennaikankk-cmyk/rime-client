#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import shutil
import subprocess
import time
import zipfile
from pathlib import Path

JAVAC = Path(r"C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\javac.exe")
JAR = Path(r"d:\zl\rime-client\build\libs\rime--1.2.16.jar")
DEST = Path(r"E:\Dopamine 0.0.8\.minecraft\versions\1.21.11\mods\rime-1.2.16.jar")
OUT = Path(r"d:\zl\rime-client\scripts\_auth_out")


def kill_mc() -> None:
    subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-Command",
            "Get-CimInstance Win32_Process | Where-Object {"
            " $_.Name -match 'java' -and $_.CommandLine -and $_.CommandLine -like '*1.21.11*'"
            "} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }",
        ],
        check=False,
    )
    time.sleep(2)


def main() -> None:
    kill_mc()
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "d1").mkdir(parents=True)
    (OUT / "d1" / "G.java").write_text(
        "package d1;\n"
        "public final class G {\n"
        "    private G() {}\n"
        "    public static boolean isAuthorized() { return true; }\n"
        "}\n",
        encoding="utf-8",
    )
    subprocess.check_call(
        [str(JAVAC), "-encoding", "UTF-8", "-d", str(OUT), str(OUT / "d1" / "G.java")]
    )
    cls = OUT / "d1" / "G.class"
    tmp = JAR.with_suffix(".auth.jar")
    with zipfile.ZipFile(JAR, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
        for info in zin.infolist():
            if info.filename == "d1/G.class":
                continue
            zout.writestr(info, zin.read(info.filename))
        zout.write(cls, "d1/G.class")
    shutil.move(tmp, JAR)
    shutil.copy2(JAR, DEST)
    print("INSTALLED", DEST, DEST.stat().st_size)
    shutil.rmtree(OUT)


if __name__ == "__main__":
    main()
