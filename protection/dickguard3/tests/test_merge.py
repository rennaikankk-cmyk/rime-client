import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]

BRIDGE_JAR = ROOT / "libs" / "rimeguard-bridge.jar"


def fnv1a64_bytes(data):
    h = 0xCBF29CE484222325
    for b in data:
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return format(h, "016x")


def compile_toy(base, marker):
    """Real class with LDC strings so Pass A generates d1/P."""
    src = base / "Toy.java"
    src.write_text("public class Toy { public static String m() { return \"" + marker + "\"; } }\n",
                   encoding="utf-8")
    javac = Path(os.environ.get("JAVA_HOME", "")) / "bin" / "javac.exe"
    if not javac.is_file():
        javac = "javac"
    else:
        javac = str(javac)
    subprocess.run([javac, "-d", str(base), str(src)], check=True, capture_output=True)
    return (base / "Toy.class").read_bytes()


def run_merge(original, guard, output, *extra):
    subprocess.run(
        [sys.executable, str(ROOT / "merge.py"), "--original", str(original),
         "--guard", str(guard), "--output", str(output), *extra],
        check=True,
        capture_output=True,
        text=True,
    )


class MergeTest(unittest.TestCase):
    def test_preserves_author_classes_and_gates_all_entrypoints(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            metadata = {
                "version": "1.10.4",
                "description": "Test client",
                "entrypoints": {
                    "client": ["com.dickclient.DickClient"],
                    "main": ["com.dickclient.OtherEntrypoint"],
                },
            }
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(metadata))
                jar.writestr("com/dickclient/DickClient.class", b"original bytes")
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")

            run_merge(original, guard, output)

            with zipfile.ZipFile(output) as jar:
                self.assertEqual(jar.read("com/dickclient/DickClient.class"), b"original bytes")
                self.assertEqual(jar.read("d1/G.class"), b"guard bytes")
                merged = json.loads(jar.read("fabric.mod.json"))
                self.assertEqual(merged["entrypoints"]["client"], ["d1.G"])
                self.assertNotIn("main", merged["entrypoints"])
                # neutral edition marker only — no protection-mechanism inventory
                self.assertIn("Launcher Edition.", merged["description"])
                self.assertNotIn("水印", merged["description"])
                self.assertNotIn("心跳", merged["description"])

    def test_rime_profile_rewrites_both_entrypoints(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            metadata = {
                "id": "guizhouware",
                "version": "1.1.6",
                "description": "Rime for Minecraft.",
                "entrypoints": {
                    "main": ["dev.guizhou.GuizhouWare"],
                    "client": ["dev.guizhou.GuizhouWare"],
                },
            }
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(metadata))
                jar.writestr("dev/guizhou/GuizhouWare.class", b"original bytes")
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")

            run_merge(original, guard, output, "--profile", "rime")

            with zipfile.ZipFile(output) as jar:
                self.assertEqual(jar.read("dev/guizhou/GuizhouWare.class"), b"original bytes")
                merged = json.loads(jar.read("fabric.mod.json"))
                self.assertEqual(merged["entrypoints"]["main"], ["d1.G"])
                self.assertEqual(merged["entrypoints"]["client"], ["d1.G"])

    def test_rime_profile_rejects_unreviewed_entrypoints(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            metadata = {
                "id": "guizhouware",
                "entrypoints": {"client": ["someone.else.Entrypoint"], "main": ["dev.guizhou.GuizhouWare"]},
            }
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(metadata))
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")

            with self.assertRaises(subprocess.CalledProcessError):
                run_merge(original, guard, output, "--profile", "rime")

    def test_legacy_path_still_merges(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            metadata = {
                "version": "1.10.4",
                "description": "Test client",
                "entrypoints": {"client": ["com.dickclient.DickClient"], "main": ["com.dickclient.X"]},
            }
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(metadata))
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")

            run_merge(original, guard, output, "--legacy")

            with zipfile.ZipFile(output) as jar:
                merged = json.loads(jar.read("fabric.mod.json"))
                self.assertEqual(merged["entrypoints"], {"client": ["d1.G"]})

    def test_native_channel_stamps_required_manifest_and_bridge(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            dll = base / "rimeguard.dll"
            metadata = {
                "version": "1.10.4",
                "description": "Test client",
                "entrypoints": {"client": ["com.dickclient.DickClient"], "main": ["com.dickclient.X"]},
            }
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(metadata))
                jar.writestr("Toy.class", compile_toy(base, "pool-custody-marker-text"))
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")
            dll.write_bytes(b"fake dll payload for hashing \x00\x01\x02")

            run_merge(original, guard, output, "--native", str(dll), "--bridge", str(BRIDGE_JAR),
                      "--native-secret", "0" * 64)

            with zipfile.ZipFile(output) as jar:
                names = jar.namelist()
                self.assertIn("dev/rimeguard/bridge/NativeGuard.class", names)
                self.assertIn("META-INF/rimeguard-native.json", names)
                stamp = json.loads(jar.read("META-INF/rimeguard-native.json"))
                self.assertEqual(stamp["v"], 1)
                self.assertEqual(stamp["mode"], "required")
                self.assertEqual(stamp["h"], fnv1a64_bytes(dll.read_bytes()))
                # P1b: the pool must consult the dll for its seed
                self.assertIn("d1/P.class", names)
                self.assertIn(b"dev.rimeguard.bridge.NativeGuard", jar.read("d1/P.class"))

    def test_pool_without_native_has_no_bridge_reference(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            metadata = {
                "version": "1.10.4",
                "description": "Test client",
                "entrypoints": {"client": ["com.dickclient.DickClient"], "main": ["com.dickclient.X"]},
            }
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(metadata))
                jar.writestr("Toy.class", compile_toy(base, "plain-pool-marker-text"))
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")

            run_merge(original, guard, output)

            with zipfile.ZipFile(output) as jar:
                self.assertIn("d1/P.class", jar.namelist())
                self.assertNotIn(b"dev.rimeguard.bridge.NativeGuard", jar.read("d1/P.class"))

    def test_native_channel_rejects_missing_dll(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps({
                    "entrypoints": {"client": ["com.dickclient.DickClient"], "main": ["com.dickclient.X"]},
                }))
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")

            with self.assertRaises(subprocess.CalledProcessError):
                run_merge(original, guard, output, "--native", str(base / "nope.dll"),
                          "--native-secret", "0" * 64)

    def test_native_channel_requires_secret(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            dll = base / "rimeguard.dll"
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps({
                    "entrypoints": {"client": ["com.dickclient.DickClient"], "main": ["com.dickclient.X"]},
                }))
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")
            dll.write_bytes(b"fake dll")

            env_secret = os.environ.pop("RIMEGUARD_NATIVE_SECRET", None)
            try:
                with self.assertRaises(subprocess.CalledProcessError):
                    run_merge(original, guard, output, "--native", str(dll), "--bridge", str(BRIDGE_JAR))
            finally:
                if env_secret is not None:
                    os.environ["RIMEGUARD_NATIVE_SECRET"] = env_secret


if __name__ == "__main__":
    unittest.main()
