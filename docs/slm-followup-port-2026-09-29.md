# SLM 后续移植第一批：补给链六项（2026-09-29）

上游基线：[m1919810/SlimefunHelper](https://github.com/m1919810/SlimefunHelper) 1.21.11 分支
`326ada8b16d579534e35cba0b60b8f47d0ca3213`（本轮开工前已 fetch 确认无新提交，源码 CC0-1.0）。
按交接文档建议顺序实施：InvHelper → ChestHistory → KitReplenish → PearlESP → AntiReplenish → AntiChunkLag。
本文档是**代码与夹具层面的交付记录**；实机验收（见文末清单）尚未执行，任何胜率/性能结论都不以此为据。

## 交付内容

### 1. Replenish 扩充（上游 InvHelper）

`features/modules/player/ReplenishModule.java` + 纯规划层 `util/inventory/ReplenishPlanning.java`。

新增能力（全部默认关闭，保持既有用户行为不变）：
- `RefillOffhand`：副手低于阈值补满；AutoTotem 启用时整段让位（它拥有副手）。
- `RefillOnUse` / `OnUsePercentage`：玩家**亲自**使用物品后下一 tick 补该格（区分模块动作：快照
  匹配 + `ItemUseTracker.moduleUseInFlight()`；幽灵手防护：格子物品与使用时不一致即放弃）。
- `MergeStacks` / `MergePercentage`：低于阈值的半堆并入最大同类半堆，空光标收尾。
- `CheckDelay` / `MaxMoves`：每轮操作预算；`UseHotbarSource`：允许从快捷栏取源。
- 移动原语统一走 `moveTo`/`mergeInto`（单一授权边界）：整堆→SWAP 单包；半堆→PICKUP 三连或 QUICK_MOVE。
- 协调：不动 SwapManager 锁定的格子（AutoEat 进食、PacketMine 持工具）、使用中的手、让行外部客户端
  INVENTORY 域。**上游 auto-drop 明确不移植。**

### 2. ChestHistory 容器历史

`util/world/ContainerHistoryStore.java`（纯状态机）+ `features/modules/render/ChestHistoryModule.java`。

- 打开容器时按"最近一次玩家方块交互"关联世界坐标（1500ms 窗口，一次消费）；服务端 GUI 无坐标不记录
  ——**未打开过的容器内容绝不推断**。
- 双箱一份快照登记两格；方块被替换/另一半消失即失效；按 服务器#维度 作用域隔离，换服/换维度清空；
  条目按 `ExpireMinutes` 过期。
- 自放潜影盒：玩家自己发出的放置包（预测位置）+ 区块加载后方块验证 → `isSelfPlaced` 标记；
  预测 20 秒未出现即丢弃。
- UI：`WorldLabel` 标注"上次观察 Xs前 · N 件 · 自放"，明确**非实时内容**。末影箱按标题单独跟踪。
- 存储为静态单例，供 KitReplenish/AntiReplenish 复用。

### 3. KitReplenish 套装补给

`features/modules/player/KitReplenishModule.java` + 纯计数层 `util/inventory/KitDemand.java`
+ 命令 `kitreplenish`（save/use/list/run/stop，别名 `kr`）。

分阶段（按交接要求，不照搬 zeroTickSupplyShulker，每一步等服务器确认）：
- 阶段 A `CompactFirst`：先合堆腾位。
- 阶段 B `FromOpenContainer`：玩家**主动打开**的容器/末影箱内，按套装缺口限时取物（MovesPerTick/tick），
  绝不代开界面。连锁取物走同一通道。
- 阶段 C `ShulkerChain`（默认关）：自己背包里的补给盒（按物品 CONTAINER 组件估值选最优）→
  周边一格空位放置（`ItemUseTracker.use`，带旋转对齐与放置序列）→ 等**服务器开窗**（超时转回收）→
  只取缺口物品 → 关窗 → 持镐挖回（超时释放）。放置被回滚/方块被换/界面异常一律中止并清理。
- 中止与让行：授权拒绝、外部客户端 INVENTORY 域、死亡、断线、正在用物品、其他模块持锁。

### 4. PearlESP 归属兜底

`util/entity/PearlOwnership.java` + `TrajectoriesModule` 接入。

- 珍珠 spawn 包 `data` 字段携带主人实体 ID（1.21.11 Mojang `ClientboundAddEntityPacket.getData()`，
  无需 mixin）；玩家观测把实体 ID 绑定到 UUID+名字。
- 提醒链：活体 owner → 归属缓存 → 已记录 EID 现场解析；显示"名字（在线/离线）"或"未知归属"
  （`AlertUnknown` 可关）。未知归属**不**判定敌人（渲染仍走中性色）。
- 实体 ID 复用：同 ID 重新观测即覆盖；TTL 60s；`retain` 随珍珠消失清理；换维度/断线清空。

### 5. AntiReplenish 反补给

`features/modules/combat/AntiReplenishModule.java`（默认关）。

- 扫描身边（SearchRange 立方格，5 tick 一轮，未加载区块跳过）非自放（ChestHistory 标记）潜影盒；
  盒旁 `FriendClearance` 内有好友视为好友的盒，跳过。
- `Notify`：聊天+HUD 一次性提醒；`Highlight`：世界内描框。
- `AutoMine`（默认关）：持镐挖盒。UtilityActionGuard 全量让行：界面、用物品、GZBoost 巡航、重锤下砸、
  空战控制、甲飞换装；自己的镐锁不阻塞判定。目标消失/超时立即结束。
- **上游的开盒取物/丢弃整段不移植**（交接："取物与丢弃默认关闭"——本轮直接不做）。

### 6. AntiChunkLag 区块延迟预警

`features/modules/hud/ChunkLagModule.java` + 纯状态机 `util/hud/SignalHysteresis.java`。

- 仅预警：脚下区块未加载，或按当前速度前瞻 `LookaheadSeconds` 秒的落点区块未加载（带距离）。
- 滞回：`OnTicks` 连续采样才报警、`OffTicks` 连续干净才解除——区块边缘反复穿越不闪跳。
- 不移植上游冻结运动策略。

## 横切

- `ForeignClientProbe`：新增 `Domain.INVENTORY`；classify 增补 `invhelper`、`kitreplenish`、
  `antireplenish`（并保留原 `cheststealer/inventorytweaks/inventorymanager` 的 ARMOR 域）。
  上述模块 tick 均让行外部 INVENTORY 属主。
- 授权检查点（防线 2 红线）：Replenish/KitReplenish/AntiReplenish 的全部动作入口
  （`moveTo`/`mergeInto`/`takeFromOpenContainer`/`placeShulker`/`breakPlacedShulker`/`mineEnemyBox`）
  前置 `HeartbeatManager.authorized()`，清理路径保持无门。
  `LicenseActionHookTest` 字节码级验证扩至 659 项。
- 语言：`client_en.json` 新增 35 条（模块名、命令、提醒文案）；`ModuleNames` 增补中文显示名。
- 双开适配：本批全部动作经既有 SwapManager/ScreenGate/PendingSequences/ItemUseTracker 通道，
  动作归属统一。

## 夹具与回归

新增无头夹具：`ReplenishPlanningTest`(25)、`ContainerHistoryStoreTest`(29)、`KitDemandTest`(15)、
`PearlOwnershipTest`(11)、`SignalHysteresisTest`(11)，均已注册进 `clientRegressionTests`；
`ForeignClientProbeTest` 扩至 27 项。`check` 全量回归结果见提交记录。
（RusherHack 依用户要求不做实机验证，仅保留公共 API 适配。）

## 实机验收清单（待用户执行，未做不得声称已验证）

1. 授权拒绝/恢复：心跳 DENIED 时所有新模块停止动作、恢复后正常；清理路径无门。
2. Replenish：手动切槽、正在进食/用矛时被锁格不被抢；打开任意容器后取消——光标空、无残留；
   背包满时 QUICK_MOVE/三连点不丢物；换服后模板与待补队列清空。
3. KitReplenish：`kr save`→缺口→`kr run`；观察放置点选择、开窗等待、只取缺口、关窗、挖盒回收；
   服务器卡顿时各阶段超时回退；甲飞/吃药/矛同时开启时的让行。
4. ChestHistory：开箱记录、双箱两格、方块被替换即失效、换维度清空、标签显示"上次观察"。
5. PearlESP：敌人珍珠在其退出渲染距离后仍能归属（在线/离线），未知归属显示且不当敌人。
6. AntiReplenish：自己的盒不触发、好友旁的盒不触发、敌方盒提醒+描框；AutoMine 在巡航/下砸/进食时让行。
7. ChunkLag：区块边缘高速 GZBoost 穿越不闪跳；前瞻距离与实际相符。
8. 真实 SlimefunHelper 1.1.7.1 同装：上游 InvHelper/KitReplenish 开启时本批对应模块显示"让行"并停止动作，
   关闭后恢复接管。

## 明确不做 / 不声称

- 不移植：InvHelper auto-drop、AntiReplenish 开盒取物/丢弃、上游冻结运动的 AntiChunkLag、
  KitReplenish zeroTickSupplyShulker、上游端箱换盒（拉盒出箱）。
- 不声称任何胜率/最高速度提升；未做实机验证前不声称"已验证"。
