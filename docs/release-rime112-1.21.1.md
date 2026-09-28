# Rime 1.1.2 · Minecraft 1.21.1 · Rimeguard 3.1

2026-09-28. Aligns the maintained 1.21.1 branch with the 1.21.11 Rime 1.1.2 fixes, while retaining the older protocol, rendering, equipment, movement and translated-item adapters.

## Changes

- Default crystal logic considers reachable bases at the target's feet height on flat ground; candidate scoring preserves self-damage, minimum damage, reach and collision checks. Rusher-specific search/rotation behavior is retained.
- Crystal motion prediction, weapon/weakness damage checks, base scoring and explosion exposure calculations; TP crystal safe detonation positions and captured-position restoration; anchor, bed and piston candidate improvements.
- Automatic mace dive retains the actual smash window, accepts chestplates or empty inventory slots for unequipping wings, and restores owned equipment. Ground spear approach/charge handling is aligned using the existing translated-item metadata adapter.
- GZBoost preview/handoff, firework limits, item-use ownership and delayed armor metadata; retain the 1.21.1 native glide capture, rotation arbitration, rocket tail and all per-action authorization checks.
- TP crystal RenderDamage, color and scale settings; damage display can be enabled independently of boxes. Bed boxes remain aligned to both blocks and vanilla 9/16 height with existing placement effects.
- Active module list defaults to enabled modules, with an explicit optional watchlist. HUD, command UI, nametags and overlays share Anthropic Serif resolution and Chinese fallback. Fonts remain an optional launcher download.
- Immediate GUI meshes/textures and native empty armor-slot textures adapted to 1.21.1. Correct the Freecam Camera.setup hook to the native BlockGetter signature.
- Rimeguard 3.1 supplements existing account/device binding, signed heartbeat, integrity, watermark, obfuscation and action gates. Validation fixtures are never included in the release JAR.

## Verification

Full offline regression suite and protection tests pass. Isolated native 1.21.1 integration checks confirm flat-ground obsidian placement, crystal damage (predicted 19.118443; server loss 19.11847), four-direction bed bounds, TP damage labels, enabled module list and all three Anthropic Serif font weights. See `tools/port-probe` for the fixture.

Final artifact hashes and production dual-Java startup receipts are recorded separately after packaging. Fixed-target checks do not establish public-server acceptance or human PvP performance. Native 1.21.1 has no spear; translated spear functionality requires supported original-item metadata. Existing settings are preserved, including an explicitly disabled AutoMaceDive in old configurations.

Use the launcher to select Minecraft 1.21.1 and receive your own watermarked copy. Keep 1.21.1 and 1.21.11 in separate game instances. The foundation JAR is not an account-authorized download.
