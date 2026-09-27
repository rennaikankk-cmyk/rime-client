# Rime 1.1.2 · Rimeguard 3.1

2026-09-28。Minecraft 1.21.11；客户端版本 1.1.2，防护版本保持 Rimeguard 3.1。

- 修复 DEFAULT 水晶在实心平地漏扫脚部同高底座：仍保留距离、碰撞、自伤与目标伤害检查，Rusher 路径不改。
- 修复重锤收翼与下砸：支持主背包胸甲及空槽，自动下砸不再被普通攻击抢先打断。
- 改善地面对手的矛进攻路线、离地空间、撤离与烟花补充，保留甲飞组合的装备恢复。
- TP 水晶新增独立伤害渲染选项、颜色与缩放；TP 床框贴合两格床模型和实际高度。
- 模块列表默认自动显示开启且未隐藏的功能，兼容原手动名单模式。
- Nametag、HUD、菜单、世界标签及命令提示统一字体入口；配合登录器 1.0.1 下载 Anthropic Serif 三个字重和中文回退，重启游戏生效。

实机战斗、平地底座与字体证据分别见 `combat-five-fixes-20260928.md`、`crystal-flat-base-fix-20260928.md`、`font-unification-20260928.md`。这些是隔离存档和固定目标测试，不代表真人 PvP 胜率。发行继续经过全量回归、混淆校验和生产签名副本的双 Java 21 启动验收；最终产物与上线回执记录在 `releases/rime-1.1.2/`。


## 正式发布验收

- 构建提交 `4bf9af0`，标签 `rime-v1.1.2-rimeguard3.1`；客户端、登录器均已推送各自仓库 main，默认分支同步为 main。
- 基础包 `rime-4bf9af0-1.1.2-rimeguard3.1-obfuscated.jar`，SHA-256 `00ef9fca7ec8dcab42f076fb0c96ab7b67ef2ef1539653fa6a254f205f5589ed`。
- 生产构建 99 个任务、36,015 项混淆检查、4 套 guard Java 测试和合并测试通过。无开发授权豁免进入发行包。
- 同一生产签名副本在 Microsoft 21.0.7 和 Adoptium 21.0.12.1 启动成功，各观察 47 秒，含初始化和图集就绪后的 20 秒稳定窗口。离线测试身份的 Realms 请求失败属于预期；没有启动、混淆或授权失败。测试进程停止，专用验证账号撤销。
- 官网基础包、登录器 1.21.11 签名源和桌面 mods 的 SHA-256 一致。原始 JAR 直链继续按既有策略返回 404，会员通过登录器领取个人签名包。
- 官网首页与更新日志 HTTP 200 且哈希匹配；两个 Windows 登录器入口及校验文件均已实际下载核对。登录器 19 项 Release 测试及上线后固定证书/签名健康检查通过。
- 服务端备份 `/var/backups/guizhouware-rime112/20260928-release`；桌面旧包备份 `guizhouware-backups/rime112-20260928`。未修改共享授权服务或 Rusher 逻辑。

[官网更新日志](http://62.234.117.226/download.html#updates) · [GitHub Release](https://github.com/rennaikankk-cmyk/rime-client/releases/tag/rime-v1.1.2-rimeguard3.1)
