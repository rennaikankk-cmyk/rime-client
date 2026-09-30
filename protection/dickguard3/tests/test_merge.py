import json
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
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")
            dll.write_bytes(b"fake dll payload for hashing \x00\x01\x02")

            run_merge(original, guard, output, "--native", str(dll), "--bridge", str(BRIDGE_JAR))

            with zipfile.ZipFile(output) as jar:
                names = jar.namelist()
                self.assertIn("dev/rimeguard/bridge/NativeGuard.class", names)
                self.assertIn("META-INF/rimeguard-native.json", names)
                stamp = json.loads(jar.read("META-INF/rimeguard-native.json"))
                self.assertEqual(stamp["v"], 1)
                self.assertEqual(stamp["mode"], "required")
                self.assertEqual(stamp["h"], fnv1a64_bytes(dll.read_bytes()))

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
                run_merge(original, guard, output, "--native", str(base / "nope.dll"))


if __name__ == "__main__":
    unittest.main()
