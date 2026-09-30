# HUD 布局、杀戮目标与 NoAC 模式

在 `7d3be2f` 的水晶、TP 水晶、床与 FPS 优化基础上继续修改；完整 PR 基线仍为用户确认的 `3e59ceb`。

## 使用与行为

- HUD 自定义位置按卡片可移动区域保存锚点。分辨率、GUI 比例、卡片大小改变时只投影显示位置，恢复原布局时回到原位置。兼容旧坐标，第一次取得有效尺寸时迁移；只点击卡片不拖动不会重新保存或吸附。
- 注册原先未进入 ModuleManager 的 `KillAura`（杀戮光环）。`TargetMode=GLOBAL` 沿用全局 Targets；`CUSTOM` 单独配置玩家、盔甲架、敌对、中立、被动生物。玩家始终保留好友和 AntiBot 过滤。
- `Projectiles` 默认开启，独立于上述生物目标选项。拦截原版 `redirectable_projectile` 标签中的火球/风弹和潜影弹；排除自己所有的投掷物。保持近距离与遮挡检查，不换武器、不 TP、不等待 TPS、设置延迟或武器冷却，也不修改共享近战计时。每 tick 去重，默认最多成功攻击 8 个。
- 杀戮能在 AutoSword 关闭时独立运行。两个模块同时开启时由杀戮出手，共享计时每个客户端 tick 只推进一次；修复低 TPS 被重复放大冷却的问题，并使用物品攻击速度计算基本时长。
- AutoSword 和 KillAura 新增 `NoAC`，默认关闭。无反作弊服务器可打开 NoAC 并关闭 Rotate：保留距离/遮挡检查，不等待服务端朝向；普通近战冷却仍遵守原设置。Rotate 开启时继续提交转头请求。
- NoSlow 的 `EatMode=NoAC` 直接取消进食减速，不发送额外换槽、移动或转头包，不受 MinTPS 限制。其他物品、盾牌和方块减速保留原开关。旧模式与默认 Grim 不变。
- NoFall 的 `Mode=NoAC` 在普通下落移动包上设置落地标志，包括仅转头/状态包。无需附近地面、修正暂停或 TPS；保留摔落阈值、飞行/液体等适用条件和 PauseMace。旧 GroundSpoof/Grim 与默认模式不变。
- Rusher 水晶尊重 Rotate 关闭，不再额外等待服务端射线朝向；Rotate 开启时仍检查最终射线，伤害、安全和武器准备条件继续生效。

## 验证

- `gradlew.bat build --offline`：完整回归、重映射、混淆与最终产物验收通过。
- 新增 HUD 坐标往返、旧坐标迁移、卡片超屏恢复，共享冷却/低 TPS/切服，以及投掷物原版契约与独立分支检查；更新 Rotate 开关和授权检查。
- `gradlew.bat runClientGameTest --offline -PgzwClientTests=true -PgzwDevelopmentBuild=true`：Java 21 的实际 Minecraft 1.21.11 客户端与内置服务器通过 20 项检查。服务器确认潜影弹被击毁、火球所有者变为玩家；两者出手时近战低 TPS 计时仍处于冷却。杀戮单独开启、Rotate 关闭时在 40 tick 内连续攻击，独立被动目标覆盖全局关闭选项，并造成服务器确认的伤害。
- 同一实际客户端检查 HUD 锚点往返和 NoAC 进食取消；模拟发送事件确认 NoAC 在远离地面时修改仅转头下落包、旧 GroundSpoof 不受影响。未宣称已完成远程服务器的 NoFall 摔伤实测或反作弊兼容实测。
- 测试源码独立位于 `src/gametest`，仅显式开启测试参数时加载，不进入发行 Jar。开发资源排除尚未合并的外部分发 guard 标记，仍要求编译期开发参数与 Loader 开发环境；所有 Jar 任务拒绝开发授权构建。正常构建恢复分发 guard 标记，产物验收同时检查标记、生产开发常量为 false、测试入口/类没有打包。
- 测试框架默认选项产生原版 Anisotropic Filtering=0 日志，Fabric 测试账号产生 Realms 登录提示；测试正常退出，没有模块/Mixin 失败。

## 云南水晶待对照

用户粘贴的 GLM 汇总没有包含实现源码。当前本地源码、已知目录和 GitHub 远程中均未找到 `YunnanCrystalModule.java`。尚未比较其伤害、安全、确认序列、线程和开销，不能根据“全部验证绿”的汇总判断比 Rusher 更好。本次保留 Rusher 实现，等待用户提供完整文件目录或提交链接后再评估替换。

本次没有正式发行或部署；客户发行仍走现有 rimeguard/native 合并与验收流程。
