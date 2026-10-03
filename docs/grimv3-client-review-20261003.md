# GrimV3 名称与实现对照（2026-10-03）

结论：在已提供的样本中，GrimV3 是不同作者给某条兼容策略起的名字，不能当作跨客户端统一协议、Grim 官方版本号或当前服务器通过检测的保证。Grim 官方公开仓库仍使用 `2.0` 分支，说明了移动预测、延迟补偿和背包补偿；是否可用还取决于服务端构建、协议转换链和模块组合。

官方来源：[GrimAnticheat/Grim](https://github.com/GrimAnticheat/Grim)。下表区分源码行为、资源说明和无法验证的部分。

| 客户端 / 实际模式 | 查到的行为 | 范围与限制 |
| --- | --- | --- |
| Rime `NoSlow → GrimV3` | 主手食物/饮品、原地开始使用时建立会话；跳过首个位置包，再等待地面且含转头的位置包打开门槛，之后在满足条件的移动包上重发当前选中槽，预算 37 次。减速取消跟随会话标志。 | 已存在，不是本轮新增。注释标为 Boze V/T/U/W/X 对应实现；这只能确认 Rime 的来源注释，不能独立证明当前 Boze 核心一致。依赖移动包的 rotation 标志，不等于所有模块 Rotate 关闭时都能触发。 |
| SLM `BYPASS_GRIM_LAZY_V3` | 根据服务端自身 using-item 元数据维护状态，移动/非乘坐/物品确实减速时执行成对的 preSwap/postSwap，配合主副手或背包操作、冲刺状态恢复；有 NoSprint 选项。 | 强依赖事件顺序与背包同步，不能只复制一个切槽包。最新拉取源码仍明确注释 `fixed in 2026.0701 grim commit`：这是作者对旧路径已修复的说明，本次没有独立重现那个修复提交。 |
| Mio `GRIMV3`（菜单名实际为 **Slow**） | 使用物品且非乘坐时，只在 tick 可被 3 或 4 整除、但不可被 12 整除的时刻补偿输入减速；鞘翅滑翔时不执行该补偿。 | 每 12 tick 中 5 tick 的间歇补偿，不是全程无减速；不能直接作为空战吃苹果加速策略。普通 Grim 使用另一条副手物品交互路径。来源为先前提供样本的恢复 Java。 |
| Hachimi `ElytraFly → GrimV3` | 恢复的设置资源描述为针对 2b2t 的 packet elytra fly 策略。 | 本次能确认设置说明，未取得可可靠映射到该开关的完整执行函数，不能把资源文案当作已验证飞行能力。 |
| Hachimi `Scaffold → GrimV3` | 设置资源描述为使用新的 Grim interactions。 | 是放置交互选项，与它自己的鞘翅 GrimV3 也不是同一套算法；同样仅确认资源层意图。 |
| OpenTroll `Grim / GrimTick` | Grim 是暂存 Pong、交换主副手、进食后恢复并清空队列的状态机；GrimTick 是按物品使用 tick 决定是否取消减速。 | 检索样本未见字面 GrimV3。其队列和背包控制需要与其他模块协调，不适合整段替换 Rime。 |
| Alien `Grim / GrimPacket` | 使用物品时向另一只手发送使用交互；GrimPacket 还加入容器点击。 | 检索的 NoSlow 未见 GrimV3；容器点击有同步副作用，不直接移植。 |
| Nullpoint `Vanilla / NCP` | Vanilla 取消减速；NCP 在使用物品时重发当前槽位。 | 该样本 NoSlow 没有 GrimV3。 |
| Boze / Rusher / Future | 先前提供的 Boze、Rusher 材料主要为 loader；Future 样本未定位到可靠可读的对应核心。 | 不以 loader 名称、Rime 注释或猜测补出核心逻辑。 |

## 来源固定

- Rime：本轮基线 `d4a537866b6caeb3b4ae0a64a8fb9e7651ae6d10`，`src/main/java/dev/guizhou/features/modules/movement/NoSlowModule.java`。历史引入提交 `141e5c6` / `c71f71a` 描述为 Boze GrimV3 eat mode。
- SLM：2026-10-03 `git fetch origin 1.21.11` 后远程分支为 `fe5ed1545d7b607746d038a8523da08f0b6753aa`；未改动原参考仓工作树。[固定源码](https://github.com/m1919810/SlimefunHelper/blob/fe5ed1545d7b607746d038a8523da08f0b6753aa/src/main/java/me/matl114/hacks/modules/move/NoSlowDown.java)。此文件相对原参考 `326ada8` 无差异。重点为元数据监听、`preSwap/postSwap`、`applyPreTickModify` 和枚举注释。
- OpenTroll：参考仓 `3d4f1eeaf95f580f116ea553a309e02403ebf114`，`src/main/java/shit/module/movement/NoSlow.java`。
- Mio：`2026-09-20/new-chat/work/mio-rotation-reference/src/main/java/me/mioclient/module/movement/NoSlow.java`；重点 `NoSlowMode` 与 `onTickMovement`。
- Hachimi：`2026-09-20/new-chat/outputs/hachimi-recovered/beta/module-catalog.json` 的 `elytrafly-grimv3-config`、`scaffold-grimv3-config`，与 `resources` 语言包对应。恢复 Java 中未直接匹配到相同字符串，因此执行实现保留未知。
- Alien / Nullpoint：同批 `alien-render-reference` / `nullpoint-render-reference` 的 movement/NoSlow.java。

## 对 Rime 的结论

本轮没有把这些旧模式整段移植，也没有改变现有 NoSlow 默认值。值得参考的是 SLM 的成对恢复、服务端状态驱动和模块间物品使用所有权；Rime 已有进食/背包所有权与甲飞恢复逻辑，需要按具体状态缺口验证后再接入。复制历史已修复路径无法证明改善当前 Grim 兼容性。

本轮完成的是代码/资源对照与客户端公共渲染开销优化，没有进行新的 Grim 服务器通过率测试或玩家对战胜率测试。后续如验证模式，必须固定服务端 Grim 构建、客户端与服务端版本、Via 路径、延迟、食物与手别，并观察完整吃完、库存恢复、纠正包和服务端告警。
