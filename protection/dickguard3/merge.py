# -*- coding: utf-8 -*-
"""Merge the original client jar with the guard while preserving author classes."""

import argparse
import json
import os
import zipfile


HERE = os.path.dirname(os.path.abspath(__file__))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Build a guarded client jar")
    parser.add_argument("--original", required=True, help="path to the original client jar")
    parser.add_argument("--guard", default=os.path.join(HERE, "build", "guard-obf.jar"))
    parser.add_argument("--output", default=os.path.join(HERE, "dist", "dickclient-1.10.4.jar"))
    args = parser.parse_args(argv)
    original = os.path.abspath(args.original)
    guard = os.path.abspath(args.guard)
    output = os.path.abspath(args.output)
    if output in (original, guard):
        parser.error("output must differ from both input jars")
    if not os.path.isfile(original) or not os.path.isfile(guard):
        parser.error("both the original client jar and built guard jar must exist")

    os.makedirs(os.path.dirname(output), exist_ok=True)
    with zipfile.ZipFile(original) as zin, zipfile.ZipFile(guard) as zguard:
        source_names = zin.namelist()
        guard_classes = [name for name in zguard.namelist() if name.endswith(".class")]
        if "fabric.mod.json" not in source_names:
            parser.error("original jar has no fabric.mod.json")
        if "d1/G.class" not in guard_classes:
            parser.error("guard jar has no d1/G.class entrypoint")
        if len(source_names) != len(set(source_names)) or any(name in source_names for name in guard_classes):
            parser.error("duplicate jar entries would be produced")

        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                if info.filename == "fabric.mod.json":
                    mod = json.loads(data.decode("utf-8"))
                    mod["description"] = (
                        mod.get("description", "")
                        + " Launcher Edition：经启动器分发，含授权水印/心跳/完整性校验与官方版标记。"
                    )
                    mod.setdefault("custom", {})["dickguard"] = "3.0"
                    # Remove other entrypoints that could run before the guard.
                    expected = {"main": ["dev.guizhou.GuizhouWare"], "client": ["dev.guizhou.GuizhouWare"]}
                    if mod.get("id") != "guizhouware" or mod.get("entrypoints") != expected:
                        raise ValueError("Rime adapter requires the reviewed main and client entrypoints")
                    mod["entrypoints"] = {"main": ["d1.G"], "client": ["d1.G"]}
                    data = json.dumps(mod, ensure_ascii=False, indent=2).encode("utf-8")
                zout.writestr(info, data)
            for info in zguard.infolist():
                if info.filename.endswith(".class"):
                    zout.writestr(info.filename, zguard.read(info.filename))

    print("final ->", output, os.path.getsize(output), "bytes")
    with zipfile.ZipFile(output) as jar:
        mod = json.loads(jar.read("fabric.mod.json"))
        print("entries:", len(jar.namelist()), "| guard:", [n for n in jar.namelist() if n.startswith("d1/")])
        print(mod["version"], mod["entrypoints"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
