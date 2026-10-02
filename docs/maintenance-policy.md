# Supported versions

As of 2026-10-02, routine Rime development and releases target **Minecraft 1.21.11** on `main`.

Minecraft **1.21.1** is an opt-in compatibility port on `codex/minecraft-1.21.1`, available to members through the launcher. Synchronize the latest 1.21.11 changes only when the owner explicitly requests **“更新 1.21.1”** (or an equivalent explicit instruction). Do not automatically build, synchronize, or publish 1.21.1 during routine 1.21.11 releases. Its publication must preserve the active 1.21.11 artifact and keep 1.21.11 selected first in the launcher.

Each requested port needs its own compatibility checks, production obfuscation, protection merge, signed startup checks, and download verification. Native 1.21.1 has no spear item; spear support depends on a compatible Via translation retaining the original item metadata.

The Windows launcher is maintained in `rennaikankk-cmyk/rime-launcher`; protection is maintained in `rennaikankk-cmyk/rimeguard`. This policy does not change existing account authorization.
