"""Build test-only intermediary mixins; never add these JARs to a release.

Usage: python build.py --javac PATH --mixin PATH --output DIR
Place fov-probe.jar in an isolated production smoke profile to assert 30..160.
Also place mio-style.jar there to reproduce Mio's required ModifyConstant.
Use -DfovCompat.otherCap=200 -DfovCompat.expectedCap=200 for the larger-cap case.
No auth, account data, other clients, or game behavior beyond FOV is modified.
"""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--javac', type=Path, required=True)
p.add_argument('--mixin', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parent
classes = a.output / 'classes'
classes.mkdir(parents=True, exist_ok=True)
subprocess.run([str(a.javac), '-proc:none', '--release', '21', '-cp', str(a.mixin),
                '-d', str(classes), str(root / 'MioStyleOptions.java'), str(root / 'FovProbe.java')], check=True)
for name, cls in [('fov-probe', 'FovProbe'), ('mio-style', 'MioStyleOptions')]:
    with zipfile.ZipFile(a.output / (name + '.jar'), 'w') as jar:
        jar.writestr('fabric.mod.json', json.dumps({
            'schemaVersion': 1, 'id': name.replace('-', '_'), 'version': '1.0.0',
            'environment': 'client', 'mixins': [name + '.mixins.json'],
            'depends': {'minecraft': '1.21.1'},
        }))
        jar.writestr(name + '.mixins.json', json.dumps({
            'required': True, 'package': 'compat.fov', 'compatibilityLevel': 'JAVA_21',
            'client': [cls], 'injectors': {'defaultRequire': 1},
        }))
        path = 'compat/fov/' + cls + '.class'
        jar.write(classes / path, path)
print(a.output.resolve())
