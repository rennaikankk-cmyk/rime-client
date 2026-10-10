# SLM 后续移植交接（2026-09-29）

## 已完成并发布

Rime **1.1.5 / rimeguard3.1**，仅 Minecraft **1.21.11**。功能提交 `4c454192e8a58317d5aabc8b0d937ee6177126b2` 已 push 到 main，并发布到 [GitHub Release](https://github.com/rennaikankk-cmyk/rime-client/releases/tag/rime-v1.1.5-rimeguard3.1)。官网日志、桌面 mods、登录器水印下载源及网站存档已同步。

发行包 SHA256：`f336b60ef64e4925befb9d8e9dd27bd33da471c553d7bb7121b181e4415b6ef5`。构建通过；同一候选包的签名验证副本通过 Microsoft 21.0.7 与 Adoptium 21.0.12.1 实际启动。详见 [发布验收记录](release-rime115-verification.json)。

SLM ArmorFly 已移植，默认关闭，包含背包找装备、耐久预留、落地关闭选项、巡航优先及其他客户端接管后的恢复。实际同装官方 SlimefunHelper 1.21.11 / 1.1.7.1 验证过。**用户要求不再验证 RusherHack**；只保留公共 API 适配和测试夹具覆盖，不得声称已实机验证。参见 [甲飞验证记录](slm-armorfly-verification.json)。

## 上游基线与结论

本轮已 fetch 官方 [m1919810/SlimefunHelper](https://github.com/m1919810/SlimefunHelper)。1.21.11 分支最新为 `326ada8b16d579534e35cba0b60b8f47d0ca3213`，与本次移植所用基线一致，没有额外的新提交。发布页标签是 [v1.1.7](https://github.com/m1919810/SlimefunHelper/releases/tag/v1.1.7)，本次实际同装 jar 的版本是 **1.1.7.1**。源码许可证 CC0-1.0。

以下是源码对比后的后续候选，**尚未实施，也不是胜率或性能实测结论**。优先补补给、信息与操作协调，不整体替换 GZBoost 或现有空战决策。

## 建议顺序

### 1. InvHelper：合堆、副手补充、按使用事件补货（高优先）

- 上游：[InvHelper.java](https://github.com/m1919810/SlimefunHelper/blob/326ada8b16d579534e35cba0b60b8f47d0ca3213/src/main/java/me/matl114/hacks/modules/inv/InvHelper.java)，关注 `autoStack`、`offHandStack`、`autoMerge`、`onPostUseItem`、`onPostUseItemOn` 和每轮操作预算。
- Rime 的 `features/modules/player/ReplenishModule.java` 目前主要是背包 9–35 格补快捷栏，已有空槽模板、组件匹配和槽位稳定等待；不等同于通用合堆与副手补充。
- 首先扩充现有 Replenish，统一协调 AutoTotem、AutoEat、武器切换与甲飞。使用中的物品不要被抢槽；禁止默认搬入自动丢弃功能。

### 2. KitReplenish：按配装清单补齐整套物资（高优先）

- 上游：[KitReplenish.java](https://github.com/m1919810/SlimefunHelper/blob/326ada8b16d579534e35cba0b60b8f47d0ca3213/src/main/java/me/matl114/hacks/modules/inv/KitReplenish.java)，包含 kit 请求、物品排序、潜影盒筛选、末影箱补给和容器事务。
- Rime 尚未找到对应的完整 kit 补给链。价值在烟花、图腾、水晶、黑曜石、食物等持续补货。
- 分阶段做：玩家背包整理 → 明确选择的已打开容器 → 潜影盒/末影箱链。先等待服务端窗口与物品状态确认，不直接照搬 `zeroTickSupplyShulker`。

### 3. ChestHistory：容器内容记录与自放潜影盒识别（中高优先）

- 上游：[ChestHistory.java](https://github.com/m1919810/SlimefunHelper/blob/326ada8b16d579534e35cba0b60b8f47d0ca3213/src/main/java/me/matl114/hacks/modules/inv/ChestHistory.java)，关注 `onOpenHandledScreen`、`onPlaceShulkerBox`、`findDoubleChest`、`isShulkerBoxPlacedBySelf` 和末影箱缓存。
- Rime 的 ShulkerPreview 是物品内容预览，不能直接代替世界容器历史。历史记录可服务 KitReplenish，也能避免对自己的补给盒触发敌方补给逻辑。
- UI 必须标注“上次观察”，不能把缓存显示成实时内容。缓存按服务器、维度、位置隔离，处理双箱、方块被替换、过期与换服；不能推断没打开过的容器内容。

### 4. PearlESP：补齐珍珠归属兜底与未知归属显示（中优先）

- 上游：[PearlESP.java](https://github.com/m1919810/SlimefunHelper/blob/326ada8b16d579534e35cba0b60b8f47d0ca3213/src/main/java/me/matl114/hacks/modules/survival/PearlESP.java)，`buildText` 结合直接 owner、WorldManager 缓存、owner entity ID 与玩家列表区分在线、离线、未知。
- Rime 已有轨迹和落点预测。`render/TrajectoriesModule.java:81` 的珍珠提醒要求 `getOwner() instanceof Player`，这里有可确认的显示兜底缺口；不要据此断言整个空战珍珠检测都缺失。
- 接入现有显示/威胁信息结构即可；未知归属不自动认定敌人。处理实体 ID 复用、缓存 TTL、离线和维度切换，不重写现有弹道预测。

### 5. AntiReplenish：识别并干扰对手潜影盒补给（中优先，依赖 3）

- 上游：[AntiReplenish.java](https://github.com/m1919810/SlimefunHelper/blob/326ada8b16d579534e35cba0b60b8f47d0ca3213/src/main/java/me/matl114/hacks/modules/combat/AntiReplenish.java)，范围扫描、开盒、取物/丢弃、自动关界面和挖盒；借助 ChestHistory 跳过自放盒。
- Rime 尚无这一整套流程。建议先提供标记/提醒和可选挖掘，再考虑主动容器操作；取物与丢弃默认关闭。
- 加好友与自放盒排除。所有操作绑定当前服务端容器，遇到关界面、换目标、人工操作立即结束，不得影响逃生、吃药或巡航控制。

### 6. AntiChunkLag：未加载区块预警（低优先，小改即可）

- 上游：[AntiChunkLag.java](https://github.com/m1919810/SlimefunHelper/blob/326ada8b16d579534e35cba0b60b8f47d0ca3213/src/main/java/me/matl114/hacks/modules/move/AntiChunkLag.java)。
- Rime 的 FlightCollisionSnapshot 与 GuangxiElytraModule 已检查未加载区块，所以不是缺整套功能。可补 HUD 原因提示和状态滞回，减少边界反复触发。
- 不直接移植其冻结运动策略；先测延迟、区块边缘和高速 GZBoost，避免牺牲正常巡航速度。

## 不要误判成新优势

- AntiCrystal 的弩/烟花处理、AutoThrow 环境判断、取消发包后的 PendingSequences/ack 等此前已吸收；先做差异检查，勿重复搭一套基础设施。
- 最新 `326ada8` 正在删除 ElytraBot 的实验性矛代码；不能把“上游最新”当成更强矛/重锤方案，不整段覆盖 Rime 现有空战。
- `RecipePreview.java` 当前主体是注释，不能列为现成可用配方预览。Slimefun 生产辅助另行按实际入口审查，暂不排在空战补给前面。
- AutoTotem 的 `handleTotemSwapSuccess` 在提交异步点击后就调用，不等于服务端已确认成功；不能宣传成可直接移植的可靠确认状态机。

## 接手入口与验收

工作区根：`C:/Users/renna/Documents/Codex/2026-09-27/kan`。

- Rime 仓库：`work/rime-audit`；上游：`work/slimefun-upstream`；甲飞实测资料：`work/slm-lab`；本次发行脚本/回执：`work/release-rime115`。
- 先读 Rime 根 `AGENTS.md`，保留授权、水印、混淆、签名完整性、AI 守卫与默认英文/中英文设置。仅维护 1.21.11，不恢复 1.21.1 下载。
- 每次接手先检查 git 状态和上游更新，保留用户已有改动。上面六项只是本轮建议，实施范围由用户下一步确定。
- 新增背包/容器流程要统一动作归属，复用并核对 SwapManager、ScreenGate、PendingSequences。现有 `util/compat/ForeignClientProbe` 的领域和模块名单**不覆盖所有补给模块**，需扩充 KitReplenish/InvHelper 等冲突识别，不能因已有双开适配就假设兼容。
- 验收至少包含延迟/拒绝更新、手动切槽、打开后取消、背包满、空光标收尾、换服、甲飞/吃药/矛同时运行，以及真实 SlimefunHelper 同装下接管和恢复。按用户要求不做 RusherHack 实机验证，不触碰其水晶逻辑。
- 先做 1，再用 3 支撑 2；4 可独立实施。5、6 后置。任何新胜率/最高速度结论均需实际对照，不复用旧战报当证据。
