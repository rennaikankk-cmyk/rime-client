# 自动床不放置：1.21.11 根因与修复

## 复现与根因

用户反馈：下界开启自动床，完全不放置；Rotate 状态不确定。

旧版 `GuangxiBedModule` 在候选扫描和待执行动作中都读取客户端
`EnvironmentAttributes.BED_RULE`，要求 `.explodes()` 为 true。
Minecraft 1.21.11 的这个环境属性 **没有标记 syncable**，客户端维度数据不包含该属性，
读取到的是默认 `CAN_SLEEP_WHEN_DARK`。因此即使身处原版下界/末地，客户端也会报告床不爆炸。

本地实际 1.21.11 的 `EnvironmentAttributes`、`DimensionType`、`EnvironmentAttributeMap`
字节码与原生客户端测试相互印证。旧代码下界八个场景都未提交使用包，也未造成爆炸伤害；
扫描日志全部在 `dim` 阶段拒绝。这与 Rotate 是否开启无关。

## 修改

- 未同步的 BED_RULE 使用维度类型注册键识别原版 Nether/End；Overworld/Overworld Caves 禁用。
  自定义世界沿用原版维度类型时可识别。未知自定义类型仍禁用，不能从默认值推断服务器自定义规则。
  若属性将来可同步，则使用真实同步属性。候选扫描和待执行动作采用同一判断。
- 按原版 `BedBlock.getStateForPlacement`、`BlockItem.canPlace` 检查放置：
  两格均可替换且在边界内，床尾使用实际床形状检查实体碰撞；床头不额外要求实体空间为空，
  两半不强制地板支撑。仍检查实际 `BlockPlaceContext`，不发送无依附方块的空点放置。
- 放置依次检查六个邻接方块的有效表面，支持侧面/下表面。
- 箱子等交互方块借用 PlacementManager 已有的按下潜行 → 使用 → finally 恢复输入流程。
  只在放置时使用，避免把床的引爆交互变成潜行放置。
- 距离按真正点击的表面判断；床头不可达时尝试床尾。爆炸伤害仍以床头中心计算。
- 保留服务端方块确认、动作重试、自伤上限、防自杀、换槽/进食避让、授权检查及
  Rotate 关闭时只选择服务器当前朝向的规则。伤害上界剪枝仍先于碰撞和射线计算。

## 外部实现研究

阅读时间：2026-10-03。下列链接指向上游源码；它们的分支可能继续变化。

- [Meteor BedAura](https://github.com/MeteorDevelopment/meteor-client/blob/master/src/main/java/meteordevelopment/meteorclient/systems/modules/combat/BedAura.java)：
  将方向旋转与床放置结合，检查真实床方块后交互；没有用未同步 BED_RULE 阻断下界扫描。
- [BlackOut BedAuraPlus](https://github.com/KassuK1/BlackOut/blob/main/src/main/java/kassuk/addon/blackout/modules/BedAuraPlus.java)：
  两格地板要求属于特定 5B5T 模式，默认关闭；分别判断实体碰撞和交互面，允许选择可交互的床半格。

本次借鉴放置几何和交互选择思路，实现继续使用 Rime 自身的已确认动作状态机和伤害检查。

## 验证方法与证据

`tools/bed-auto-probe` 启动原生客户端和集成服务器，正常启用自动床，走完整的玩家目标扫描和 tick。
没有写入 pending 位置，也没有直接调用私有放置/引爆方法。
客户端 RemotePlayer 用于目标选择，重叠位置的服务端静止僵尸用于真实伤害测量。
驱动玩家为生存模式、未附魔下界合金全甲、无抗性效果。
伤害仅累计 `BAD_RESPAWN_POINT` 类型，避免环境伤害被误记为床爆炸。

新增用例：四种几何 × Rotate 开/关，共八项；另测主世界禁止、末地、缺床、自伤上限、
副手床、主背包换床、无目标、实体方块占位、只能够到床尾，共十七项。
箱子用例限制 VerticalScan=0 并让驱动玩家多退一格，在默认自伤上限下专门验证箱子侧面。
末地移除测试世界的龙，并在首次龙战初始化后重建测试场地：诊断实际发现原本空气位置
`(0,101,0)` 被新生成出口祭坛的 bedrock 覆盖，导致早期末地用例被地形阻挡。
只够到床尾的用例使用服务器预放床，且背包无床。

工作区证据（仓库旁的 `work` 内）：

- 旧代码：`bed-auto-lab/baseline/results.json`、`bed-auto-baseline.log`。
- 修复后的完整自动链路：`bed-auto-lab/verified3/results.json`、`bed-auto-lab/verified3.log`。
  **17/17 通过**：12 个正向场景观察到服务器床方块及真实床爆炸伤害，5 个阻止场景均未发送使用包。
  箱子用例记录到箱子侧面交互和临时潜行输入；床尾用例记录到 FOOT 交互及真实爆炸伤害。
- 原有爆炸回归：`blast-lab/bed-fix-20261003/complete.txt`、`blast-lab/bed-fix-20261003.log`。
  76 项检查通过，包含 18 组实际爆炸伤害与 5 条模块操作链；普通床、TP 床以及 TP 后位置/角度恢复通过。
- 完整生产构建：`gradlew --offline build` 通过，131 个任务；`ObfuscatedJarTest` 44,698 项检查通过。
  日志：`bed-fix-full-build.log`。开发测试模组未进入生产包。
- 只有 Gradle BUILD SUCCESSFUL 不算用例通过：还必须检查 complete.txt、failure.txt 和每行 success。

原生测试日志中的 Realms 开发账号 JWT 提示和一次 Mojang 公钥请求超时属于外部在线服务，
不影响已经完成的集成服务器测试；没有将它们隐去或计为模块验证成功的依据。

此测试证明所列原版场景的完整自动放置与引爆，不代表真实玩家对战胜率、Grim 兼容或特殊服务端规则。
本次针对主线 1.21.11；没有同步 1.21.1 或发布会员包。
