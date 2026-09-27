# Blast probe

Runs a real 1.21.11 client and integrated server against a **copy** of a supplied test world. Requires the cached Gradle dependencies and Java 21. Run from the repository root:

```powershell
tools/blast-probe/run-probe.ps1 -Name final01 -WorldPath 'C:\path\to\test-world' -JavaHome 'C:\path\to\jdk-21'
```

Choose a new run name each time. Results and full logs are written to the sibling `blast-lab` directory. Success requires `complete.txt`, absence of `failure.txt`, and a successful client process. The fixture Jar contains only test classes; the main client is launched with the explicit development flag and is never packaged with that flag.

The full run compares 18 real explosions: six geometries/profiles for crystals, anchors in the Overworld, and beds in the Nether. Crystal predictions exercise both TP and piston modules. It then drives production action methods for anchor place/charge/detonate, normal bed, TP bed, TP crystal, and attack after a server piston pushes a crystal. Checks include observed blocks/entities, actual target health loss, and restored TP position/angles. Extra assertions cover intermediate anchor self risk, unsafe TP positions and refreshed piston armor attributes.

The server-owned target is a stationary zombie. The fixture drives private action entry points; it does not claim automatic player acquisition, a complete automatic piston construction sequence, competitive PvP throughput, long-range server compatibility or an anti-cheat result. Creative flight plus Resistance V isolate the driver from blast damage/knockback during action tests. Geometry/profile oracle targets still receive real survival damage. Vanilla does not disclose another mob's full effects; the known-resistance oracle explicitly sends its effect metadata to the test client.

`-StartChain 0..4` is a focused debugging option that skips the damage oracle and starts at the selected action chain. Its completion file reports the actual counts; use the default full run for acceptance.
