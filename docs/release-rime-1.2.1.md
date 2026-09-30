# Rime 1.2.1 · P1c.1 按账号池租约 + edition 脱敏 + 壳 condy 托管

2026-09-30。在 1.2.0 native 激活之上收紧三处防护面；launcher 仍 ≥1.2.0，无需新 exe。

## 防护口径

- **P1c.1 按账号租约**：模板 jar 构建期仍混全局 L；下载水印将 `d1/P.data` / `runtime/dyn/B.data` 重键为 `HMAC(L, user)`；健康心跳下发同一 HMAC（不再下发全局 L）。他人会话抠出的租约解不开本账号以外的水印副本。
- **edition.txt v2**：`leaseCommit=SHA-256(lease)`，租约原文不落盘。
- **壳 condy 托管**：发行壳带 `--native-secret` + `--pool-lease`；`runtime/dyn/B` 解密混 `NS()`/`LS()`（与 `d1/P` 同纪律）。无会话/无 dll = 乱码。
- 双通道与 dllHashes 不变；旧 launcher 继续 3.2 legacy。

## 校验记录

- 构建源码：`3268ff1`（`mod_version=1.2.1`）；rimeguard `979022f`。
- 发行候选：`rime-3268ff1-1.2.1-rimeguard3.3-native-shelled.jar`，SHA256 `f0d9bc5a6c67663857dadbd57574d1882e78313ad38690a7d664379c0efcf8d1`。含 `d1/P.data` + `runtime/dyn/B.data`。
- dll 未变：FNV `f1c13f68b7bb4551`，SHA256 `17c55074475d6fe5dcb45956150b838bf0b231b799c395ecd0e6bf1a76a4b650`。
- 合并测试 10/10；guard 四套件；壳行为一致性；VerifyMerge 2695/2695（模板态 + 生产全局 L）。
- 服务端：`rgPoolLease` + 水印重键与 jar **同发**。

## 已知事项

- 1.2.0 模板 jar（无 `P.data` / 期望全局 L）与 1.2.1 服务端不兼容——必须同发。
- 壳 RESEARCH「v2」中 MBA/切块仍未做；1.2.0 文档称「壳 v2」实为拼接 condy + nanoTime 谓词。
- 轮换扳机未扣；CI 仍本地构建 dll。
