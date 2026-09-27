# Rime 1.1.1 · rimeguard3.1 更名发行

2026-09-28，Rime 的防护名称由 dickguard 改为 rimeguard。客户端版本保持 1.1.1。

新包使用 custom.rimeguard=3.1；客户端授权门和发布校验兼容旧标记。冲突双标记、空值、未知版本或缺失 guard 入口仍被拒绝。内部握手及密码学域分隔串保持兼容，上游名称与历史发行记录保留。共享认证服务未覆盖。

- 构建源码：7b26cc2；标签：rime-v1.1.1-rimeguard。
- 发行包：`rime-7b26cc2-1.1.1-rimeguard3.1-obfuscated.jar`。
- SHA256：`a4ed83ba0bff79f9b81a3ecec001281393a7be4b9aa8ad9bfa6e06022313b1e1`。
- 85 个客户端回归任务、35,960 项混淆检查、4 套 guard Java 测试、1 项合并测试、27 项发布通道测试通过。
- 同一生产签名副本在 Microsoft 21.0.7 和 Adoptium 21.0.12.1 上完成 Rime 初始化、纹理图集创建及 20 秒稳定观察；测试进程已停止，专用验证账号已撤销。
- 官网首页、更新日志、网站基础包、登录器下载源和桌面 mods 同步。会员继续通过登录器领取个人水印签名副本。

本次只更名与兼容适配，战斗模块及 Rusher 未改。历史伤害/动作测试属于此前逻辑版本，本次未重复进行真人对战测试。

[官网更新日志](http://62.234.117.226/download.html#updates) · [构建标签](https://github.com/rennaikankk-cmyk/rime-client/tree/rime-v1.1.1-rimeguard)
