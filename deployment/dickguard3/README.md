# dickguard3.0 服务端配套

基于 2026-09-27 现场取得的 gzwx-auth/server.js，应用 server.patch 前必须检查生产基线并备份。guard3.js 放同目录。保留所有旧登录、旧心跳、HWID、下载水印与签名路径。新客户端的 entryChallenge/entryCipher 纳入原签名响应；按账号 capability subject、会话和客户端维护挑战链，拒绝重复 nonce、错误回带。服务重启可重新建立链；保留最近两个挑战以恢复一次丢失响应。该证据不等于不可伪造的客户端身份。

确认 mismatch 仍走原可归属完整性封禁路径；unavailable 连续三次只返回签名警告并记录计数，恢复后清零；env 只记录观察，不封禁。Rime 入口只给有 1.21.11 下载权限的账号，免费 Dick 账号不能通过指定 client 获得 Rime 授权。

node backend-test.cjs <候选server.js> 使用临时签名密钥、内存账号与假文件系统验证，不读写生产账号。生产依赖路径仍由现有 /opt/gzwx-auth 提供。

发行仍须由管理通道生成水印和完整性签名，绝不把未签名的合并产物当成可用客户副本。部署采用现有发行锁、SHA256 基线检查、原子替换和可恢复备份。
