# dickguard3.0 服务端配套

基于 2026-09-27 现场取得的 gzwx-auth/server.js，应用 server.patch 前必须检查生产基线并备份。guard3.js 放同目录。保留所有旧登录、旧心跳、HWID、下载水印与签名路径。新客户端的 entryChallenge/entryCipher 纳入原签名响应；按账号 capability subject、会话和客户端维护挑战链，拒绝重复 nonce、错误回带。服务重启可重新建立链；保留最近两个挑战以恢复一次丢失响应。该证据不等于不可伪造的客户端身份。

确认 mismatch 仍走原可归属完整性封禁路径；unavailable 连续三次只返回签名警告并记录计数，恢复后清零；env 只记录观察，不封禁。Rime 入口只给有 1.21.11 下载权限的账号，免费 Dick 账号不能通过指定 client 获得 Rime 授权。

node backend-test.cjs <候选server.js> 使用临时签名密钥、内存账号与假文件系统验证，不读写生产账号。生产依赖路径仍由现有 /opt/gzwx-auth 提供。

发行仍须由管理通道生成水印和完整性签名，绝不把未签名的合并产物当成可用客户副本。部署采用现有发行锁、SHA256 基线检查、原子替换和可恢复备份。

## dickguard3.1 / Rime 1.1.1

在已经部署的 3.0 发布通道上顺序应用 jar_release-3.1.patch 与 test_jar_release-3.1.patch。它们仅新增对明确版本 3.1 的接受，继续要求双 d1.G 入口、guard 类和原始 Rime 类；未知 guard 版本和缺失类仍拒绝。生产应用前校验基线 SHA256，运行完整发布通道测试，锁定发布流程并备份，原子替换后重启 web-api。本次不覆盖已更新的 gzwx-auth/server.js、guard3.js 或 Dick subject pin 配套。

## rimeguard3.1 品牌更名

在 3.1 发布通道上应用 jar_release-rimeguard.patch 和 test_jar_release-rimeguard.patch。新版元数据使用 custom.rimeguard=3.1，继续兼容旧 dickguard 标记；双标记冲突、空值、未知版本、缺失 guard 类和双入口均拒绝。协议握手、密码学域分隔串及共享认证服务不改。更名发行仍须双 Java 21 签名启动验收及三处分发同步。
