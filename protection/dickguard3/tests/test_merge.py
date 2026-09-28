import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]


class MergeTest(unittest.TestCase):
    def test_preserves_author_classes_and_gates_all_entrypoints(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            original = base / "original.jar"
            guard = base / "guard.jar"
            output = base / "merged.jar"
            metadata = {
                "id": "guizhouware",
                "name": "Rime",
                "version": "1.10.4",
                "entrypoints": {
                    "client": ["dev.guizhou.GuizhouWare"],
                    "main": ["dev.guizhou.GuizhouWare"],
                },
            }
            with zipfile.ZipFile(original, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(metadata))
                jar.writestr("com/dickclient/DickClient.class", b"original bytes")
            with zipfile.ZipFile(guard, "w") as jar:
                jar.writestr("d1/G.class", b"guard bytes")

            subprocess.run(
                [sys.executable, str(ROOT / "merge.py"), "--original", str(original),
                 "--guard", str(guard), "--output", str(output)],
                check=True,
                capture_output=True,
                text=True,
            )

            with zipfile.ZipFile(output) as jar:
                self.assertEqual(jar.read("com/dickclient/DickClient.class"), b"original bytes")
                self.assertEqual(jar.read("d1/G.class"), b"guard bytes")
                merged = json.loads(jar.read("fabric.mod.json"))
                self.assertEqual(merged["version"], metadata["version"])
                self.assertEqual(merged["name"], "Rime")
                self.assertEqual(merged["custom"]["rimeguard"], "3.1")
                self.assertNotIn("dickguard", merged["custom"])
                self.assertEqual(merged["entrypoints"]["client"], ["d1.G"])
                self.assertEqual(merged["entrypoints"]["main"], ["d1.G"])


if __name__ == "__main__":
    unittest.main()
