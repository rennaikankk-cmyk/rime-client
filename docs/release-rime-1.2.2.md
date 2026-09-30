# Rime 1.2.2 · P1c.2 租约绑定本机 HWID

2026-09-30。在 1.2.1 按账号租约之上，把池密钥拆成「服务器半 ⊕ dll 本机 HWID 半」；launcher 仍 ≥1.2.0，无需新 exe。

## 防护口径

- **P1c.2 HWID 绑定**：水印目标 = `HMAC(L, account.v2|user) ⊕ HMAC(S, hwid.v1||ngHwid)`；心跳只下发服务器半；`LS()` 走 `NativeGuard.leaseMix()`（dll 内拼）。Java `lease()` 只有服务器半。
- **明文停留**：`adoptLease` 把字节交给 dll 后清 Java 副本；`edition.txt` 仍只写 `leaseCommit`。
- **共存**：旧 1.2.1（无 `pool` 字段）仍收 `HMAC(L, account.v1|user)`；仅新 jar 发 `pool=2`。先部署服务端再切 `nativeVersions`。
- **C 未做**：未加新的 Java 布尔授权门 / 未搬功能进 dll；现有 `authMaterial` + 池密钥已足以让 `return true` 拿不到池明文。

## 校验记录

- 客户端 `mod_version=1.2.2`；rimeguard 含 bridge/native/LS→leaseMix。
- 发行候选：见同目录后续 checksum 提交 / GitHub Release。
- dll 新构建（auth.v2 不变，增 adopt/mix）：FNV 与 SHA256 见 Release 与交接增量。
- 合并测试 11/11；guard 四套件；壳行为一致性；VerifyMerge 全量回环；rig 覆盖 v1+v2 租约。

## 领取说明

- 需**重新领取**客户端（登录器自动更 jar+dll）。
- 旧 1.2.1 在重新领取前仍可用（服务端继续代发 v1 租约）。
- 首次领取 1.2.2 前需已有一次 native 在线心跳（绑定 `ngHwid`），否则 prepare 会提示先完成在线验证。
