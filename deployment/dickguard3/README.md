# dickguard3.0 服务端配套

基于 2026-09-27 现场取得的 gzwx-auth/server.js，应用 server.patch 前必须检查生产基线并备份。guard3.js 放同目录。保留所有旧登录、旧心跳、HWID、下载水印与签名路径。新客户端的 entryChallenge/entryCipher 纳入原签名响应；按账号 capability subject、会话和客户端维护挑战链，拒绝重复 nonce、错误回带。服务重启可重新建立链；保留最近两个挑战以恢复一次丢失响应。该证据不等于不可伪造的客户端身份。

确认 mismatch 仍走原可归属完整性封禁路径；unavailable 连续三次只返回签名警告并记录计数，恢复后清零；env 只记录观察，不封禁。Rime 入口只给有 1.21.11 下载权限的账号，免费 Dick 账号不能通过指定 client 获得 Rime 授权。

node backend-test.cjs <候选server.js> 使用临时签名密钥、内存账号与假文件系统验证，不读写生产账号。生产依赖路径仍由现有 /opt/gzwx-auth 提供。

发行仍须由管理通道生成水印和完整性签名，绝不把未签名的合并产物当成可用客户副本。部署采用现有发行锁、SHA256 基线检查、原子替换和可恢复备份。

## rimeguard P1c 池租约（09-30）

server.patch 已更新：`createGuard3(Date.now, {lease: (ac, q, username) => rgPoolLease(username, ac)})`，guard3 握手调用带第 5 参 `r.u`。`rgPoolLease` 与 `rimeguard.poolLease` 解析随 RG 块交付（参照 gzwx-auth-server.js 参照副本；未配置 poolLease 时返回 null，data.lease 省略，行为与旧部署一致）。

部署顺序（强制）：1) config.json 的 rimeguard 节加入 `poolLease`（与构建 `--pool-lease` 同值，hex64）；2) 应用 server.patch + RG 块更新并重启；3) 才能发行以该租约合并的 jar。次序颠倒=新客户端全量乱码。同一时期并行发行的 custody 构建必须共用同一租约；轮换租约会同时废弃所有旧 jar，属发行决策，需公告强制更新。

### 已部署（2026-09-30 16:52 +0800）

生产 `62.234.117.226:/opt/gzwx-auth` 已上线：server.js（RG 块 + rgPoolLease + lease 化 guard3 接线 + 换机/管理解绑清 ngHwid）、guard3.js（lease 透传版）、`rg-secret.txt`（600，生产 native secret）、config `rimeguard = {secretFile, dllHashes: [f1c13f68b7bb4551], banThreshold: 3, poolLease}`。备份在服务器 `/root/backup-p1c-20260930/`。

生产 dll：由 rimeguard-native HEAD（c46ae86）以生产 secret 本地构建（`RIMEGUARD_NATIVE_SECRET=… cargo build --release --manifest-path native/Cargo.toml`），FNV-1a64 = `f1c13f68b7bb4551`，已登记 dllHashes。密钥不进仓库：生产 native secret 与 poolLease 存本机 `work/release-secrets/rimeguard-prod.json`（workspace 内、仓库外）+ 服务器 rg-secret.txt / config.json。

验证：换前 `node backend-test.cjs` PASS；换后服务 active，`https://127.0.0.1:8443/api/heartbeat` 畸形 400、伪 token 走到签名响应（旧客户端零影响——lease 字段只在新客户端消费）。链路全证（真混淆 guard + 生产 dll + 生产租约）：dll+租约=明文，无租约/错租约/无 dll 均乱码，4/4。

下一次 custody 发行：`RIMEGUARD_NATIVE_SECRET=<生产 secret> RIMEGUARD_POOL_LEASE=<生产 lease> python merge.py --original … --guard build/guard-obf.jar --native <rimeguard-prod.dll 路径> --bridge libs/rimeguard-bridge.jar`。dll 分发仍走启动器签名通道（bridge 的加载顺序注释）。注意：rimeguard-native 的 GitHub Actions CI 自 09-30 起持续失败（早于 P1c），dll 目前一律本地构建，恢复 CI 后需确认 Actions secret 与本地生产 secret 一致。

## dickguard3.1 / Rime 1.1.1

在已经部署的 3.0 发布通道上顺序应用 jar_release-3.1.patch 与 test_jar_release-3.1.patch。它们仅新增对明确版本 3.1 的接受，继续要求双 d1.G 入口、guard 类和原始 Rime 类；未知 guard 版本和缺失类仍拒绝。生产应用前校验基线 SHA256，运行完整发布通道测试，锁定发布流程并备份，原子替换后重启 web-api。本次不覆盖已更新的 gzwx-auth/server.js、guard3.js 或 Dick subject pin 配套。

## rimeguard3.1 品牌更名

在 3.1 发布通道上应用 jar_release-rimeguard.patch 和 test_jar_release-rimeguard.patch。新版元数据使用 custom.rimeguard=3.1，继续兼容旧 dickguard 标记；双标记冲突、空值、未知版本、缺失 guard 类和双入口均拒绝。协议握手、密码学域分隔串及共享认证服务不改。更名发行仍须双 Java 21 签名启动验收及三处分发同步。
