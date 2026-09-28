# Rime 1.1.4 — Aerial combat / Rimeguard 3.1

Minecraft 1.21.11 remains the only maintained client. This update repairs aerial mace pursuit and its coordination with ArmorFly and GZBoost.

- Preserve legacy ArmorFly preferences and enable armor switching while combat owns flight; preserve manually enabled ArmorFly after pursuit ends.
- Recognize the marked ViaBackwards mace representation when connecting through the supported legacy protocol. Ordinary axes and renamed items are not treated as maces.
- Plan glide exit and natural mace drops using achievable movement, track horizontal alignment before descending, and take viable adaptive strike windows earlier.
- Use Gentle climb angles during GZBoost mace pursuit while preserving the selected manual cruise mode and its speed formulas. Reuse attached rockets and retain the physical movement envelope.
- Keep committed evasion headings briefly, avoid redundant escapes from a mace rising below the player, and anticipate pearl arrival threats. Real damage, range, collision, sight and authorization checks remain in force.

Validation against the original SlimefunHelper 1.21.1 client and original 729.nbt: the final candidate won all 10 standard native NoAC rounds and all 6 additional diagonal/height-difference/low-altitude rounds. The final GZBoost batch finished 6 wins, 3 losses and 1 draw in 10 rounds. Both clients used identical equipment and alternating sides. These limited local mace samples do not establish public-server or spear-mode win rates.

GZBoost used actual ViaFabricPlus 1.20.3–1.20.4 protocol translation through ViaProxy to a vanilla 1.21.11 weapon backend. This is not a native 1.20.4 mace server or anti-cheat validation. No new manual peak-speed measurement is claimed. A separate defense-velocity experiment failed to improve results and was withdrawn before release.

Rimeguard 3.1, per-copy watermarking, account/device binding, signed integrity and heartbeat checks remain required. Rusher code is unchanged. Production build, two-runtime signed startup and publication hashes are recorded in the release verification record after deployment.

## Published 2026-09-28

- Source: `2e261d7ba7ab9cf3b07edd4c95e4e14e7130591e`; GitHub tag: `rime-v1.1.4-rimeguard3.1`.
- Base artifact: `rime-2e261d7-1.1.4-rimeguard3.1-obfuscated.jar`.
- SHA256: `2378ccd7dc33cd0ca3f3b27b2610bdde02d14b7ecf426d8eed12d68c6aefa09a`.
- Full production build: 103 tasks; obfuscated validation: 36,452 checks; guard regression and merge checks passed.
- The same signed validation copy passed initialization, atlas creation and a stable window on Microsoft 21.0.7 and Adoptium 21.0.12.1. Only expected offline Mojang/Realms authentication messages and the existing Windows counter warning appeared; no client startup failure was found. Both owned test processes stopped and the dedicated validation account was revoked.
- Website base artifact, launcher watermark source and desktop mods have matching SHA256. The previous desktop JAR was retained as `.bak`. Public HTML hashes match the reviewed pages; unsigned raw JAR URLs remain blocked. The launcher executable is unchanged, and Minecraft 1.21.1 remains retired.

See `release-rime114-verification.json` for receipts. Members obtain individual signed copies through the launcher.
