# Aura / Offhand 升级记录

本轮在 rc22 源码基础上改造；普通版和专用版同步。版本号不变，尚未上传官网。

## 已实现

- AutoSword 的界面、标准命令名称改为 **Aura**；AutoTotem 改为 **Offhand**。内部存档 ID 保留，旧按键、旧命令、自定义名称继续有效。
- Aura：Single 保持当前目标；Switch 跟随排序；Multi 在 MaxTargets 范围内按正常冷却轮换，每次最多一个攻击。支持距离、准星水平角、生命排序，FOV、玩家／动物／敌对／中立、裸装、命名生物、驯服生物、幼年过滤。好友始终排除。目标类型设置现在归 Aura 自身管理。
- Range 默认 3，实际受玩家交互距离属性约束；WallsRange 默认 0。优先尝试目标近点、中心、头部及下部的可见点，攻击前再次检查实际转头射线和墙体。
- Rotate / YawStep / MoveFix 在 Aura 内独立配置；转头未真正提交到目标方向时继续等待。关闭 Rotate 后仍需实际朝向目标。
- WeaponMode：None 使用手中物品；Require 要求已持指定武器；Swap 自动选热栏武器。SwapAction：Silent 攻击后立即还槽；Normal 切换并保留；SwapBack 持有至失去目标、暂停或关闭模块后还槽。可选择剑、斧、重锤、三叉戟或全部。
- 以实际属性替换主手修饰符计算攻速与伤害，不再硬编码武器冷却。普通攻击按伤害／时间估计选武器；重锤落击时按单次伤害，Smart 纳入目标护甲、韧性、致密及破甲附魔。Breach / Density 可优先相应附魔。目标无对应属性时，不错误计入亡灵杀手／节肢杀手／穿刺。
- ShieldMode：Break 对正面举盾目标优先用斧；Ignore 跳过；None 正常处理。
- AttackCD 默认 1；TPS 修正只作用于经过的时间。CustomDelay + HitDelay 可改用固定服务器 tick 间隔。攻击包被取消不消耗 Aura 冷却；手动或其他模块成功提交攻击也会重置共享观察时钟，避免同一冷却内重复出手。
- PauseEating、Multitask、PauseScreen、SwitchDelay（等待目标／武器计划稳定）、CrystalPriority；保留空战模块的武器控制优先级和就近重锤落击机会。
- Offhand 保留自动图腾用途：移除爆图腾事件里的异步清空副手，改为根据当前物品状态在 tick 中补充，避免旧回调清掉新换入物品。本轮没有声称完整复刻 Mio 的副手食物／致命伤害预测功能。

## TP 往返模式

默认关闭。TPRange 默认 12、范围 4–32；TPStep 默认 3、范围 1–5。先尝试分段直达，受阻时进行最多 384 次扩展的六方向 A* 搜索；只允许已加载区块、世界边界内、整个玩家碰撞体能够通过的路径。每条路线最多 48 个中间点；不进行穿墙或加载远处区块。找不到路径的目标短暂跳过，让其他目标有机会被选中。

攻击前以最终落点计算瞄准及交互距离。每段移动验证最终本地提交的位置／角度；任意一段被取消或被改到其他位置就停止攻击。回程在 finally 中执行；即使攻击异常、部分进入、途中授权失效也尝试返回，回程失败时阻止后续攻击并重试。原始摄像机位置保持不变。

StopOnSetback 默认开启：最近一轮 TP 后收到服务器位置纠正，停止 TP，保留近距离 Aura；重新开关模块后可重试。骑乘、鞘翅滑翔及空战模块控制武器期间不执行 TP。

本地成功提交包并不是服务端确认。服务器距离检查、移动检测、回拉及无敌时间仍可能使 TP 或攻击失效；没有在用户目标服务器上完成兼容性实测。

## 配置迁移

- 旧 Delay 改用 AttackCD=1，清除旧的固定武器冷却／重复 TPS 缩放。
- 旧 Criticals=true 迁移至 CritMode=SMART；false 迁移至 OFF。旧 CritsWithSword / Strict 删除。
- SMART：地面正常攻击，跳跃上升时等待，下落时攻击。ONLY：正常陆地场景仅在自然下落时攻击；水中、攀爬、骑乘或滑翔不等待不可用的自然暴击条件。新实现没有旧版上移发包暴击。
- 原存档本身不修改；迁移在导入副本上进行。重新保存后使用新字段。已有新字段不被覆盖。

## 参考及边界

- Mio：OpenMio 本地参考版本 c711c61f69151fe7d8efc0fc1349075347885a61。参考目标／墙距、武器及副手命名；修正其先选最优目标再排除命名实体导致无备用目标的设计问题。https://github.com/GzSakura1338/OpenMio
- Hachimi Beta：仅确认解包目录中的 Aura / Offhand 设置与静态结构。Native 方法体仍未完整恢复，不将配置名当成已经核实的执行逻辑。
- TP：参考 LiquidBounce 的公开模块说明及关联源码，采用路径、往返、位置纠正停止的设计；适配代码为本项目重新实现。https://liquidbounce.net/docs/modules/combat/tpaura 和 https://github.com/CCBlueX/LiquidBounce/blob/2b0edfcf20774df173d20e6ed7e26f582f12ae9b/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/combat/tpaura/ModuleTpAura.kt
- 护甲和 Breach 公式核对当前 Minecraft 1.21.11 CombatRules 字节码及 data/minecraft/enchantment/breach.json；Breach 每级减去 0.15 护甲减伤比例。

## 验证

- AuraPolicyTest：冷却、TPS、多目标、过滤顺序、武器护甲估算、A* 阻挡／预算、往返异常与配置迁移。
- ModuleNamesTest：旧名称、标准名称、自定义名称解析。
- LicenseActionHookTest / ObfuscatedJarTest：新增 Aura tick、最终攻击、TP 出发以及 Offhand tick／补图腾授权检查；回程和还槽清理保持可执行。
- Minecraft 实际客户端开发测试：独立 aura-gametest 测试包，开发授权只用于不可发行的 runClient；28 项集成检查覆盖真实事件总线、实际发送／取消、转头关、切槽失败、Multi 轮换、TP 分段往返、墙距和关闭清理。
- AuraPolicyTest 62 项通过；普通版 LicenseActionHookTest 417 项、ObfuscatedJarTest 30008 项通过；专用版分别 435 / 30879 项通过。两版完整 build 均通过。
- 可重复运行的集成测试位于 `tools/aura-fixture/`，入口 `run.ps1`。测试配置显式初始化，避免上一次运行的墙距等设置影响后续结果。
- 未把开发测试参数写入发行配置；本轮不包含官网部署，也未完成候选发行 JAR 的双 JDK 启动验收。
