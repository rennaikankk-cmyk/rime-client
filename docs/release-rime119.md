# Rime 1.1.9 发布记录

## Published 2026-09-30

Source `88f2a0d813ac856271fc3101192cb44b567201d4`; tag `rime-v1.1.9-rimeguard3.2`.

SHA256 `d87ef6f85269af4267dc1044bf89d6c65be8af85e93fcffb2a4e529063b50435`. Website, launcher source and desktop match. Both signed startup checks passed (Microsoft 21.0.7, Adoptium 21.0.12.1), owned test processes stopped, dedicated validation account revoked. Ships the MP4 main-menu background playback fix (jcodec pure-Java streaming, offline-verified 36/36 frames) and the Chinese-default interface flip with revision-2 migration. rimeguard 3.2 acceptance: 2695 pooled strings, 660 hostile-renamed classes, zero plaintext residue, runtime pool round-trip. See `release-rime119-verification.json` for receipts.

## 版本口径说明

线上实际分发的 1.1.9 是 rimeguard **3.2**（本记录，tag `rime-v1.1.9-rimeguard3.2`，SHA256 `d87ef6f85269af4267dc1044bf89d6c65be8af85e93fcffb2a4e529063b50435`）。`docs/release-rime-1.1.9.md` 记录的是同日并入主干的 rimeguard3.3 native 基建（休眠态，launcher dll 未分发，产物未上线，激活目标 1.2.0）——3.3 的构建产物没有发布到官网/登录器。下一版对外版本号请从 1.2.0 起跳。
