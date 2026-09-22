# rc22 GZBoost cumulative release candidate (2026-09-22)

Versions: combat.8 (1.21.11), mc1211.8 (1.21.1), direct.13 (dedicated 1.21.11).
This freezes the cumulative retained source since combat.4 / mc1211.4 / direct.9.
The intermediate .5-.7 / .10-.12 reports describe local candidates, not production releases.
Final publication is recorded separately by artifact SHA256 after build, dual-JDK startup,
authorization checks and guarded deployment. Historical reports retain their original scope.

## Flight

Grim / NoAC replace the expanded flight mode controls. Essential settings remain visible;
retired configuration fields migrate without replacing explicitly saved combat ranges.
GZBoost is an independent Grim checkbox, off by default, with no sprint-key binding.
It enables accelerated manual coupled cruise, descent and direction-sensitive climb when
dual-view protocol support is present. Unsupported connections announce single-view fallback.
Observed motion carries inertia through rocket expiry; redundant rotation synchronization
packets are avoided. Boosted manual flight overlaps rockets when required; ordinary and
combat flight reuse attached rockets. Strict vertical climb does not silently add horizontal motion.
AdaptiveSlowdown is optional and off by default. Correction recovery still clears stale
actions; enabling adaptation reduces the controlled budget and restores it gradually.

Measured on a local survival Paper 1.20.4 / Grim 2.3.73 server with a 1.21.11 development
client, ViaFabricPlus target 1.20.3-1.20.4, original movement checks, no exemption, and
increased per-player chunk load/send rates (400/400): last-15-second server displacement
reached about 160 horizontal blocks/s level, 280 descending, 215 upward along an axis,
204 upward diagonally and 31.8 strictly vertical. These are distinct actions and recorded
windows, not simultaneous speeds or a universal/server maximum. Another descent trial
experienced a vanilla correction. Directional climb can cause real lateral swing/drift and
increases rocket use. Only 1.21.11 development flight was measured in-world; the other
variants require separate runtime acceptance and do not inherit a server-speed guarantee.
See gzboost-climb.md and flight-speed-grim.md for raw evidence references and limits.

## Retained cumulative fixes

Combat steering, phase hysteresis and bounded prediction reduce unnecessary direction
changes; collision, final rotation, range, authorization and main-thread interaction checks
remain. Mace climb and descent planning, spear damage-speed thresholds, protocol-mapped
weapon recognition, held-use armor swapping and rocket reuse were revised. Combat menus
hide internal tuning constants. Crystal/anchor/piston/Aura use conservative new-profile
interaction defaults without adding separate Grim switches or overriding saved ranges.
These are code changes, not evidence of a consistent combat win-rate advantage. The latest
isolated mace contact candidate was rejected and is not included. See air-combat evidence
and legacy729 reports for negative results and incomplete combat goals.

Authentication, HWID, signed integrity, watermarking and ProGuard protections remain.
The dedicated JAR reuses its existing credential and watermark, remains remotely revocable,
and is signed separately before testing. No membership reset or mandatory hardware rebind.
