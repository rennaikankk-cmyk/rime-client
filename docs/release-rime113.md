# Rime 1.1.3 — Rimeguard 3.1

Minecraft 1.21.11 is now the only maintained and downloadable client version. Minecraft 1.21.1 is retired from the launcher and website lists; its historical release records remain available.

- English is the default client language. Existing configurations migrate to English once; a subsequent manual Chinese selection persists.
- Switch immediately under Client → ClickGui → Language. Client-owned module names, settings, descriptions, commands, HUD labels and notifications share the language catalog. Saved IDs and user-defined text remain stable.
- The Windows launcher moves to its own repository, rennaikankk-cmyk/rime-launcher, with Claude-inspired warm light/dark styling and optional Anthropic Serif font installation/repair.
- Rimeguard 3.1, watermarking, account/device checks, signed integrity and heartbeat protection remain in the production release.

Validation: full client check passed (92 Gradle tasks). An isolated in-game language probe passed 3,618 checks covering legacy migration, Chinese persistence, stable configuration IDs, hover-cache switching and registered module/setting text; English/Chinese screenshots were inspected. Final obfuscated production startup evidence is recorded separately after the two-runtime release check.
