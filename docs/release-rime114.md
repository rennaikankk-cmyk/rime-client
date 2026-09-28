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
