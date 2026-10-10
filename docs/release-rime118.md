# Rime 1.1.8 发布记录

## Published 2026-09-30

Source `6f374aa4835e2d7841f8ba8167532873c54a68df`; tag `rime-v1.1.8-rimeguard3.2`.

SHA256 `6b2060c1348a313d835fb7cb15af4d7e70b9b0bd41cb8ef381e2f09c231d03b0`. Website, launcher source and desktop match. Both signed startup checks passed (Microsoft 21.0.7, Adoptium 21.0.12.1), owned test processes stopped, dedicated validation account revoked. Obelisk-inspired feature batch (MaceSmash, AutoWeapon + shared WeaponSelector, NewChunks, StashFinder, Supplies HUD, explosion/entity-destroy echo placement, shared blast settlement math) plus rimeguard 3.2 business-layer hardening (2689 pooled strings, 658 hostile-renamed classes, zero plaintext residue, runtime pool round-trip). See `release-rime118-verification.json` for receipts.
