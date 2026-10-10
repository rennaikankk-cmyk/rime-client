# Rime 1.2.2 · P1c.2 租约绑定本机 HWID

2026-09-30。在 1.2.1 按账号租约之上，把池密钥拆成「服务器半 ⊕ dll 本机 HWID 半」；launcher 仍 ≥1.2.0，无需新 exe。

## 防护口径

- **P1c.2 HWID 绑定**：水印目标 = `HMAC(L, account.v2|user) ⊕ HMAC(S, hwid.v1||ngHwid)`；心跳只下发服务器半；`LS()` 走 `NativeGuard.leaseMix()`（dll 内拼）。Java `lease()` 只有服务器半。
- **明文停留**：`adoptLease` 把字节交给 dll 后清 Java 副本；`edition.txt` 仍只写 `leaseCommit`。
- **共存**：旧 1.2.1（无 `pool` 字段）仍收 `HMAC(L, account.v1|user)`；仅新 jar 发 `pool=2`。先部署服务端再切 `nativeVersions`。
- **C 未做**：未加新的 Java 布尔授权门 / 未搬功能进 dll；现有 `authMaterial` + 池密钥已足以让 `return true` 拿不到池明文。

## 校验记录

- 客户端源码：`e7b0d7e`（`mod_version=1.2.2`）；rimeguard `2532b1e`。
- 发行候选：`rime-e7b0d7e-1.2.2-rimeguard3.3-native-shelled.jar`，SHA256 `f4af207af1e147f24c8dce27c0de5ec7dd5e2bd3abd3bea035a086bdcfb1c361`。含 `d1/P.data` + `runtime/dyn/B.data`。
- dll 新构建：FNV `20b199fccbdf61d8`，SHA256 `71088d10e7fb9908a5784d6ae12cebf410c25a6774d76026aff757c16b70ce6a`（旧 FNV `f1c13f68b7bb4551` 仍在 dllHashes）。
- 合并测试 11/11；guard 四套件；壳行为一致性；VerifyMerge 2695/2695；rig 覆盖 v1+v2 租约。
- 生产已切：server.js 双协议 + `nativeVersions` → 1.2.2 + `poolProtocol=2`。

## 领取说明

- 需**重新领取**客户端（登录器自动更 jar+dll）。
- 旧 1.2.1 在重新领取前仍可用（服务端继续代发 v1 租约）。
- 首次领取 1.2.2 前需已有一次 native 在线心跳（绑定 `ngHwid`），否则 prepare 会提示先完成在线验证。
