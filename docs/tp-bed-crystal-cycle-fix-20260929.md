# TP 水晶、自动床与水晶换点故障修复（2026-09-29）

承接同日交接《TP 水晶、自动床与水晶换点故障交接》。基线 `8adc3c8`（远端无新提交，已 fetch 确认）。
交接确认的三条控制流缺陷本轮全部修复；未回滚平地补扫（`4bf9af0`），未删除 TP 临时站位自伤检查（`8ec7dbe`），
未动 Rusher 独立路径。**本轮仍未做实机验证**——修复以源码缺陷为准，任何"已修复用户现场"的结论都要等
普通生存装备的实机验收（见文末矩阵）。

## 1. DEFAULT 水晶：旧点等待不再被误读成"缺底座"

文件：`src/main/java/dev/guizhou/features/modules/combat/GuangxiCrystalModule.java`。

- `findBestPlace` 现在区分"没找到可执行点"与"旧循环还占着这一 tick"：新增
  `placeSearchWaiting`（锁仍生效且本轮无可执行点时为真），主循环的 `baseWins` 在
  `crystalTarget == null && placeSearchWaiting` 时不再铺新底座。原反例
  （A pending → 锁 A → 全部跳过 → 误判无点 → 铺 B）从根上关闭。
- 锁的挑战规则从"无遮挡伤害上界 bound"改为**最终有效伤害**：`recordPlace(airPos, damage)` 记住
  在途循环的最终伤害 `lastPlaceDamage`；候选只有 bound 超过 `lastPlaceDamage + LOCK_RELEASE_MARGIN`
  才值得评估，接受时还要用评估出的最终伤害压过它。一个 bound 很高但实际不可执行的点再也解不开
  正在工作的锁。锁仍会在旧点不再是候选（底座没了）时立即释放，不会锁死。
- 旧点无进展时的自然出路保持不变：pending 窗口过期且无活晶 → `lastPlaceAir` 清空 → 等待态解除，
  此时若真无可执行点，底座路径重新打开。

## 2. TP 水晶：规划与引爆条件统一，先完成当前循环

文件：`src/main/java/dev/guizhou/features/modules/combat/GuangxiTPModule.java`。

- **引爆可行性前置**：`detonatableFromSafe(center, airPos)` —— 非 legacy 时用与引爆侧完全相同的
  `TeleportBlastPosition.find(...safeDamage)` 过滤未来水晶。`findBaseNear` 与 `findBaseOpportunity`
  都只在候选即将胜出时做此检查（`dFeetRej` 计数）。"放得下却炸不掉"的点不再消耗黑曜石。
- **可供晶预检**：新增 `crystal.partnerCanPlaceCrystal()`（水晶补给 + `weaponControlBusy` 共用暂停）。
  TP 在放置自然底座水晶与铺新底座前先 `crystalReady()`（`dSupplyRej` 计数）；缺晶/武器控制忙时
  不烧放置节拍，更不铺底座。
- **循环状态**：`tpBasePlaceChain` 成功后记录 `pendingCycleBase`；观测到水晶出现即移交引爆侧，
  观测窗（`max(800ms, 3×PlaceDelay)`）过期即放弃等待。`cycleInProgress` 另把"目标旁存在可引爆活晶"
  视为循环占用：**有未完成循环时不申请新底座**（`dCycleSkip` 计数）。自然底座上的放置不受影响
  （不花底座材料，保留两晶在途的吞吐）。
- **站位搜索扩展**（`TeleportBlastPosition`）：单环 16 方向 → 三环内收（外环角错开）×
  高度 {0,+1,-1}/{0,+1}，共 ~98 个样本仍按"离真实位置最近"排序。普通装备+环上 3.7 格自伤 10.85
  超过默认上限 8 的问题由此获得真实解空间，而不是删自伤检查或抬上限；真的无安全解时
  规划直接拒绝（`dFeetRej`），与交接的数值结论一致。
- legacy (`1.12`/全局旧协议) 路径保持原样：不做站位检查、不移动三明治。

## 3. 自动床：本地无进展有界，失效候选短期避让

文件：`GuangxiBedModule.java` + 新 `util/combat/BedCandidateWatch.java`（12 项夹具检查）。

- 新增 `LocalTimeoutTicks`（默认 40）：候选创建后，本地动作（放置/引爆）持续 false 且无任何
  `sent()` 进展达到该窗口 → `stageDiagnostics.blocked("localTimeout")`、放弃候选并把该 foot 位
  置回避 `窗口+20` tick，扫描会重选别的点。**不伪造 `sent()`**——BedSequence 仍只管真正提交过的
  动作（36 项既有语义检查未动）。服务器确认阶段（WAIT）不受本地超时干扰。
- `actionDeferred` 细分：TP 模式引爆找不到安全站位单独记 `noTpFeet`；材料缺失已有 `material`。
- **扫描漏斗计数**：新增 `Debug` 开关，每秒输出 `[BedDiag] scan: targets/dim/cell/range/dmg/self/tpFeet`
  各级筛选数量——"选点前就被筛空"从此可见，不再需要靠猜（维度、装备门槛、站位、避让各自归因）。

## 4. TP 诊断计数拆分（交接路线第 2 步）

`TPDiag` 由混合的 `sent/swapFail` 拆为：`base/crys/brk`（三类提交）、`obsv`（目标旁观测到活晶）、
`noTgt/noBase/oppNull/obsFail`、`rej[supply/cycle/feet/ent/dmg/self]`。原 `dSwapFail` 从未递增，已删除。
默认关闭，开启后每秒一行，字段可直接对应"只看到黑曜石"（base>0 而 crys=0）与"放了不炸"
（crys>0 而 brk=0 / feet>0）。

## 明确不做 / 待现场

- 未回滚平地补扫；未全局提高 MaxSelfDamage 或删除自伤/站位检查；未把 legacy 路径套用现代几何。
- 未做：完整实机验收（下表）、真实 SlimefunHelper 同装回归、客户配置/JAR 版本核对（交接中用户
  尚未提供）。旧 blast-probe 夹具（创造+抗性V）不能当作本次修复的证明。

## 实机验收矩阵（承接交接第 8 节，待执行）

| 场景 | 必须观察 |
|---|---|
| 双方静止、平地、无现成底座 | 底座→晶→真实爆炸一次完成；旧点 pending 期间不再连铺 B/C/D |
| 已有合格底座+活晶/延迟确认 | 先等待/处理旧循环；失败有期限，不无限垫块 |
| TP 普通装备（无抗性V）普通/困难 | 无安全解时不铺底座（feet 拒绝可见）；有安全解时完整走完放-炸 |
| TP 缺晶 / AutoSwap=NONE / 虚弱 | `rej[supply=..]` 可见，底座与晶不再分裂 |
| TP legacy 与实际 Via 协议 | 分别验收，无站位检查副作用 |
| 床：本地持续拒绝（hit/朝向/范围） | `localTimeout` 后放弃并重选，200 tick 卡死不再复现 |
| 床：无候选场景 | `[BedDiag]` 漏斗计数能指出是哪一级筛空 |
| 床普通/TP 模式 × 下界/末地 | 默认设置走通；两半床确认、真实伤害对照 |
| 锚/水晶/床并发 | 共享交换与旋转无饥饿 |

## 验证（本轮已做）

- 新夹具：`BedCandidateWatchTest`（12 项）。
- 定向回归：BlastSequenceTest(36)、CrystalSelectionPolicyTest、LicenseActionHookTest(659，含
  `recordPlace` 签名变更后的 `doPlace` 字节码检查)、CrystalAttackHookTest、CrystalAttackWindowTest、
  PacketActionHookTest、BlastStageDiagnosticsTest、CombatPlannerTest 全部通过。
- 全量 `check` + `build`（含混淆与产物校验）结果见提交。
