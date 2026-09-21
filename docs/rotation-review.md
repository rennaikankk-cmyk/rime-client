# 模块转头实现复查

2026-09-21。分析 Hachimi Beta、OpenMio 与 GuizhouWare；未发布新版本。

## 选项归属

Rotate 是对应模块内的选项，没有独立的全局 Rotations 模块。

| 功能 | 当前接入 |
|---|---|
| AutoCrystal | 原 Rotate 开关；独立 YawStep、MoveFix；放置、普通击破共用本模块的设置 |
| AutoAnchor | 原 Rotate 开关；独立 YawStep、MoveFix；放置、充能、引爆共用 |
| Bed | 原 Rotate 开关；独立 YawStep、MoveFix，适用于普通床；TP 路径保留原有移动交互方案 |
| PistonCrystal | 新增 Rotate；独立 YawStep、MoveFix；活塞定向与击破。关闭 Rotate 时放活塞仍检查现有方向，不能放反 |
| PacketMine | 保留 StartRotate、EndRotate；两者启用时显示本模块 YawStep，控制实际开始／结束挖掘。AutoMine 委托 PacketMine 执行，继承其设置 |
| AutoSword、Surround 清水晶、FastPortal | 使用新的动作等待接口，当前维持原先默认即时转头；本轮未为这些模块添加步进设置 |
| AutoXP、KeyPotion、中键珍珠、空战攻击、SpearTP、TP 水晶 | 检查静默转头的提交结果；保持即时交互时序，本轮未增加步进设置 |

YawStep 单位为度／游戏 tick，默认 180，代表可以直接转到目标；调低后需多 tick 到位。MoveFix 仅在步进时显示，用来保持移动输入方向。已有 Rotate 开关及配置键保留。不同模块的数值互不覆盖。

TP 床、TP 水晶、幽灵水晶攻击和自带空战控制器不宣称已全部切换到通用步进。这些路径有自己的移动视点、射线或命中窗口约束，应分别验证后继续接入。

## 核心修复

- 朝向仅由完整发送事件链通过后的最终移动包更新；取消包不更新，替换包记录其真实字段。这里是本地发送检查通过，不是服务器确认。
- 静默转头必须观察到本次真正提交了带朝向的包，并且最终角度匹配目标，才允许依赖动作。
- 取消后的普通移动转头会重试；sendPosition 的 HEAD/TAIL 不再提前伪造服务器朝向，也不会无条件清除待同步状态。
- 静默包使用真实 horizontalCollision；尊重 noJitter。默认不再加入原先固定的随机大幅抖动。
- 动作步进只保留短期转头请求，不保存攻击、物品、放置回调。模块每次重试重新检查现场。未续订超过 3 tick 的动作转头请求会清除；关闭模块或 Rotate 后，下个 tick 清除对应请求；换玩家、世界或连接时清除旧状态。
- 同优先级请求更新保留排序，较高优先级的持续转头可阻止较低优先级动作抢占。原有静默发包窗口优先级仍保留。
- 完成判断检查实际提交角度；步进从实际提交角度出发，取消包不能推动虚假的进度。拒绝非有限角度，限制俯仰范围。

## 参考依据

OpenMio 固定提交 `c711c61f69151fe7d8efc0fc1349075347885a61`，用户地址重定向至 GzSakura1338/OpenMio。

- [SearchHelper4_8.java](https://github.com/GzSakura1338/OpenMio/blob/c711c61f69151fe7d8efc0fc1349075347885a61/src/main/java/me/mioclient/SearchHelper4_8.java)：getFloatArray2487 使用上一 yaw 和 wrapDegrees 限制单次偏航；do2476 做优先级选择；do2473/do978 管理转头与后续包的时序。
- [AntiCheat.java](https://github.com/GzSakura1338/OpenMio/blob/c711c61f69151fe7d8efc0fc1349075347885a61/src/main/java/me/mioclient/module/client/AntiCheat.java)：集中定义 yawStep、movementSync、rotations。这里只借鉴角度限制和动作排序思路，GuizhouWare 按用户要求保留各模块独立设置。
- [AutoCrystal.java](https://github.com/GzSakura1338/OpenMio/blob/c711c61f69151fe7d8efc0fc1349075347885a61/src/main/java/me/mioclient/module/combat/AutoCrystal.java)：调用步进助手和旋转优先级入口。重建源码依赖 loader，未运行其未知加载器，也未照搬队列和版本相关 Mixin。
- Hachimi Beta 的已提取 `assets/hachimiclient/lang/en_us.json` 第 645–650 行明确给出 Rotate、YawStep、YawStep-Limit 及跨 tick 的角度限制说明；锚有独立 Rotate。关键原生交互函数体仍未恢复。可确认设计和设置，不能将其语言描述称为已验证的底层实现。

## 验证

官网源码完整 check 通过，包括 RotationMathTest 的 10,103 项角度边界／步进检查及既有战斗、飞行、保护检查。

独立 Fabric 游戏测试运行实际 RotationManager 与网络 Mixin，25 项断言通过，观察到 6 次步进发送：静默镜头不变、noJitter、取消包、错角度替换包、无朝向替换包、非法数值、优先级、取消后重试、完成释放、短期过期、关闭 Rotate 和换世界清理。测试仅在隔离单人世界中运行，没有对战、使用客户凭据或修改授权保护。

官网与专用版源码均已同步，完整 build、check、混淆及 verifyObfuscatedJar 通过，产物检查分别为 27,847 和 28,718 项。最后一次完整构建日志分别为 `work/guizhouware-upgrades-rotation-final.log`、`work/guizhouware-direct-jar-rotation-final.log`，游戏测试日志为 `work/rotation-gametest/run-final.log`。

游戏日志有测试环境的图形／音频选项回退及离线 Realms 身份提示，没有观察到 GuizhouWare、Mixin 或转头测试失败。尚未对新发行成品执行双 JDK 验收；本轮是源码交付与构建验证，没有签发、上传或发布新的客户 JAR。不宣称实服成功率或反作弊兼容性提升。
