# Rime 1.2.7 — Minecraft 1.21.1 member port

This explicitly requested port synchronizes the Minecraft 1.21.11 Rime 1.2.7 source revision `327b8f43fd33f8d2f1cee352bdb6bc8fd4e8d2a1`. Daily maintenance remains on Minecraft 1.21.11. Future 1.21.1 synchronization requires an explicit owner request; see [maintenance policy](maintenance-policy.md).

The port includes current crystal, TP crystal and bed planning, HUD layout coordinates, projectile defense, NoAC movement options, aerial combat and GZBoost strategies, normal/enchanted apple selection and fallback, safe offhand use, and optional eating retreat. Configuration autosave, planner caches, user toggle handling, death notifications and sprint correction tracking are synchronized too.

Native adapters retain 1.21.1 equipment queries, mutable input impulses, render types/framebuffers, resource reload ownership and packet signatures. Projectile margins match native 1.21.1. The port retains the reviewed rotation arbitration and accepted-packet tracking. Protection comes from the separate rimeguard repository; obsolete embedded copies are removed.

The launcher exposes the signed member version list and labels this version **1.21.1 · 按需同步**. Minecraft 1.21.11 stays selected first. Installation uses independent version pointers and preserves the active 1.21.11 native and legacy templates, DLL, lease custody and authorization rules. Install each jar into its matching Minecraft instance; do not load both versions together.

Native 1.21.1 has no spear. The retained Via adapter recognizes seven materials only from verified original-item metadata, with supported component overrides and charge/expiry handling. It does not identify renamed ordinary swords as spears or add a stabbing action to the old protocol. GZBoost still requires a supported translation route and server settings.

Validation uses the full production regression/obfuscation gate, separate development fixture jars, actual native integrated-server crystal placement/damage, four bed orientations, TP damage labels, three font weights, server-confirmed mace smashes and equipment recovery, and nine offhand selection/safety conditions. Final release acceptance also requires per-copy signed native and legacy startup on Microsoft 21.0.7 and Adoptium 21.0.12.1, actual first-install DLL binding/redownload, and signed member download checks. Complete logs, failures and provenance are retained in the release workspace. These are compatibility checks, not a new public-server win-rate or Grim benchmark.
