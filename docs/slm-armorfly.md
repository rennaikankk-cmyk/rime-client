# SLM ArmorFly 与同装兼容

参考 [SlimefunHelper](https://github.com/m1919810/SlimefunHelper/tree/326ada8b16d579534e35cba0b60b8f47d0ca3213) 的 `ElytraExtra`（CC0-1.0）。移植其按滑翔状态恢复甲飞、背包装备查找和耐久保留思路，接入 Rime 原有装备控制器。1.2.8 原甲飞也默认采用服务器同步节奏，并支持背包和独立装备设置；SLM 仍保留自己的设置与落地关闭选项。详见[甲飞服务器兼容修复](armor-flight-1.2.8.md)。

## 使用

- Movement → **SLM ArmorFly**，配置 ID 为 `SLMArmorFly`，默认关闭。它与原 `ArmorFly` 互斥，后开启的生效。
- `AutoEquip=true`：从快捷栏或主背包查找可用鞘翅，支持主背包内胸甲；优先基础护甲值更高的胸甲。
- `MinimumDurability=5`：候选装备至少保留这些耐久点；有绑定诅咒、容器打开、鼠标持物或装备已被其他操作替换时不强行换装。
- `AutoRocket=true`：复用飞行模块现有的烟花延迟、库存与安全检查。
- `CruisePriority=true`：GZBoost 手动巡航时让出换甲，保留巡航速度；重锤下砸仍由空战装备控制器接管。
- `LandAutoDisable=false`：可选落地自动关闭。关闭甲飞时先恢复自有鞘翅；空中不直接拆掉鞘翅。
- 独立使用时仍按住跳跃起飞，不接管方向。空战开启前可先启用 SLM ArmorFly，空战不会再自动启用原甲飞替换它。

本模式在胸甲仍维持滑翔时保留胸甲，观察到滑翔结束才恢复鞘翅并重新起飞；不使用原甲飞的 `GlideTrackerDelay`。原甲飞 `ServerSync` 开启时使用同样节奏；关闭后恢复原换装时序。没有移植上游所有实验模式、包延迟或环境绕过选项，也不承诺相同反作弊通过率。

## 两种同装组合

ClickGUI → Compatibility → `ClientCompatibility=true` 默认启用。支持 Rime + SlimefunHelper（含已知 fork ID）和 Rime + RusherHack 的公开状态适配；不依赖它们才能启动，不改写对方开关或配置。

| 对方正在运行的功能 | Rime 的让行范围 |
|---|---|
| 飞行、鞘翅机器人、相关加速 | 飞行控制、空战；涉及装备的飞行也让出换甲和烟花 |
| 甲飞、自动穿甲、换翼 | 甲飞、自动穿甲、飞行及其空战装备控制 |
| 自动烟花 | 自动烟花 |
| 自动图腾 / 副手 | 自动图腾 |
| 自动进食 | 自动进食及其选槽保持 |
| Aura 类战斗模块 | 空战追击和武器控制 |

让行只覆盖适配表中的功能和 Rime 的上述控制入口，不代表所有模组、HUD 或任意第三方模块都能同时控制同一行为。若两个 GUI 使用相同按键，可在 Rime 的 Bind 中另设按键；本功能不改写已有按键。

对方接管时清除旧装备、选槽恢复记录，避免稍后按旧记录换回；SLM ArmorFly 在此前确实滑翔、仍未落地时保留恢复意图，对方关闭后重新查找当前装备并恢复滑翔。落地、回拉、重生和换世界清除这一意图。让行期间由对方负责飞行，Rime 不发送保命动作抢回控制权。

已检测到对方但公开 API 不可用时，暂停上述重叠控制，模块列表显示 `Yield: … API unavailable`；后续刻继续检测，API 恢复后解除暂停。关闭 `ClientCompatibility` 会关闭这层自动让行。

RusherHack 适配只使用 [公开模块管理 API](https://javadocs.rusherhack.org/org/rusherhack/client/api/RusherHackAPI.html) 和 [模块开关状态](https://javadocs.rusherhack.org/org/rusherhack/client/api/feature/module/ToggleableModule.html)。没有改动 Rime 原来的 Rusher 水晶逻辑、RusherHack 代码或用户配置。

## 验证范围

`ArmorSwapCycleTest` 覆盖滑翔延迟清除、每个动作窗口换装顺序、手动替换和延后恢复；`ArmorFlightHandoffTest` 覆盖让行恢复、落地和会话清理；`ForeignClientProbeTest` 覆盖两个公开 API 的开关、缺失、延迟初始化及只读约束；授权入口由 `LicenseActionHookTest` 覆盖。

实际测试结果和限制见本次验证记录及 Rime 1.1.5 发布记录。旧版对战胜率不能作为本次新增模式的胜率。
