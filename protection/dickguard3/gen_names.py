#!/usr/bin/env python3
"""Generate XOR-obfuscated agent-name literals for W.java (prints java source)."""
names = ['DumpAgent', 'RetransformDump', 'PoolExportAgent',
         'JavaAgent', 'AgentMain', 'InstrumentAgent', 'ClassDumper']
mask = [0x35, 0x5a, 0x71, 0x24, 0x6f, 0x18, 0x42, 0x3d, 0x57, 0x29, 0x4e, 0x63]
BS = chr(92)
lits = []
for i, n in enumerate(names):
    s = ''.join(chr(ord(ch) ^ mask[(i + j) % len(mask)]) for j, ch in enumerate(n))
    lits.append('"' + ''.join(BS + 'u%04x' % ord(c) for c in s) + '"')
print('        ' + ',\n        '.join(lits) + ',')
