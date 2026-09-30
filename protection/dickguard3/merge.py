# -*- coding: utf-8 -*-
"""Merge the original client jar with the guard (thin wrapper over tools/GuardMerge).

GuardMerge also applies two business-layer hardening passes before the merge:
  - string pooling: business LDC constants move into the encrypted d1/P pool
  - filesystem-hostile class names: zero-width suffixes on business classes
    (default "cfa" mode is python-zipfile-safe; "nul" also defeats python tooling
    but requires a NUL-aware release pipeline)
and rewrites fabric.mod.json with a NEUTRAL edition suffix (no mechanism inventory).

Legacy pure-python merge is kept as --legacy for diffing; new releases should use
the default path.
"""

import argparse
import json
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))


def find_java(min_major=21):
    """GuardMerge is compiled for release 21; PATH java is often an old JRE."""
    exe = "java.exe" if os.name == "nt" else "java"
    cands = []
    if os.environ.get("JAVA_HOME"):
        cands.append(os.path.join(os.environ["JAVA_HOME"], "bin", exe))
    cands.append(exe)
    for c in cands:
        try:
            v = subprocess.run([c, "-version"], capture_output=True, text=True, timeout=15)
        except (OSError, subprocess.SubprocessError):
            continue
        first = (v.stderr or v.stdout).splitlines()[0] if (v.stderr or v.stdout) else ""
        # e.g. 'openjdk version "21.0.7" ...' / 'java version "1.8.0_503"'
        ver = first.split('"')[1] if '"' in first else "0"
        major = 8 if ver.startswith("1.8") else int(ver.split(".")[0]) if ver.split(".")[0].isdigit() else 0
        if major >= min_major:
            return c
    raise SystemExit("no JDK >= %d found (set JAVA_HOME)" % min_major)


def build_tools():
    jar = os.path.join(HERE, "build", "guard-tools.jar")
    gradlew = os.path.join(HERE, "gradlew.bat") if os.name == "nt" else os.path.join(HERE, "gradlew")
    subprocess.run([gradlew, "guardToolsJar", "-q"], cwd=HERE, check=True,
                   capture_output=True, text=True)
    if not os.path.isfile(jar):
        raise SystemExit("guard-tools.jar missing after build")
    return jar


def run_guard_merge(args):
    tools_jar = os.path.join(HERE, "build", "guard-tools.jar")
    cmd = [find_java(), "-cp", tools_jar, "GuardMerge",
           "--original=" + args.original,
           "--guard=" + args.guard,
           "--output=" + args.output,
           "--profile=" + args.profile,
           "--strings=" + ("on" if args.strings else "off"),
           "--names=" + args.names]
    if args.keep_prefix:
        cmd.append("--keep-prefix=" + args.keep_prefix)
    res = subprocess.run(cmd, cwd=HERE, capture_output=True, text=True)
    sys.stdout.write(res.stdout)
    sys.stderr.write(res.stderr)
    if res.returncode != 0:
        raise SystemExit(res.returncode)


def legacy_merge(args):
    original = os.path.abspath(args.original)
    guard = os.path.abspath(args.guard)
    output = os.path.abspath(args.output)
    if output in (original, guard):
        raise SystemExit("output must differ from both input jars")
    if not os.path.isfile(original) or not os.path.isfile(guard):
        raise SystemExit("both the original client jar and built guard jar must exist")
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with zipfile.ZipFile(original) as zin, zipfile.ZipFile(guard) as zguard:
        source_names = zin.namelist()
        guard_classes = [name for name in zguard.namelist() if name.endswith(".class")]
        if "fabric.mod.json" not in source_names:
            raise SystemExit("original jar has no fabric.mod.json")
        if "d1/G.class" not in guard_classes:
            raise SystemExit("guard jar has no d1/G.class entrypoint")
        if len(source_names) != len(set(source_names)) or any(name in source_names for name in guard_classes):
            raise SystemExit("duplicate jar entries would be produced")
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                if info.filename == "fabric.mod.json":
                    mod = json.loads(data.decode("utf-8"))
                    mod["description"] = mod.get("description", "") + " Launcher Edition."
                    mod.setdefault("custom", {})["dickguard"] = "3.1"
                    expected_client = ["com.dickclient.DickClient"]
                    if mod.get("entrypoints", {}).get("client") != expected_client:
                        raise SystemExit("dick profile expects the reviewed client entrypoint")
                    mod["entrypoints"] = {"client": ["d1.G"]}
                    data = json.dumps(mod, ensure_ascii=False, indent=2).encode("utf-8")
                zout.writestr(info, data)
            for info in zguard.infolist():
                if info.filename.endswith(".class"):
                    zout.writestr(info.filename, zguard.read(info.filename))
    print("legacy final ->", output, os.path.getsize(output), "bytes")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Build a guarded client jar")
    parser.add_argument("--original", required=True, help="path to the original client jar")
    parser.add_argument("--guard", default=os.path.join(HERE, "build", "guard-obf.jar"))
    parser.add_argument("--output", default=os.path.join(HERE, "dist", "dickclient-1.10.4.jar"))
    parser.add_argument("--profile", default="dick", choices=["dick", "rime"])
    parser.add_argument("--strings", default=True,
                        type=lambda v: str(v).lower() not in ("0", "false", "off"))
    parser.add_argument("--names", default="cfa", choices=["off", "cfa", "nul"])
    parser.add_argument("--keep-prefix", default="",
                        help="extra comma-separated class prefixes exempt from both passes")
    parser.add_argument("--legacy", action="store_true",
                        help="old pure-python merge without hardening passes")
    args = parser.parse_args(argv)
    if args.legacy:
        legacy_merge(args)
        return 0
    build_tools()
    run_guard_merge(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
