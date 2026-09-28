# 原版集成服战斗回归

这是独立的开发测试模组，不进入客户端发行 JAR。它通过 Loom 启动真实 Minecraft 1.21.11 客户端和原版集成服，运行项目真实模块及网络/装备/旋转钩子，读取服务器目标的实际生命值与伤害来源。没有用手写运动模型替代游戏。

## 运行

先准备一个可正常打开的测试世界，然后从仓库根目录执行（Java 21）：

```powershell
tools/combat-probe/run-probe.ps1 -Name spear-ground -WorldPath 'C:\test-world' -Mode SPEAR
tools/combat-probe/run-probe.ps1 -Name mace-ground -WorldPath 'C:\test-world' -Mode MACE
tools/combat-probe/run-probe.ps1 -Name mxs-armor -WorldPath 'C:\test-world' -Mode MXS -Armor true
tools/combat-probe/run-probe.ps1 -Name spear-air -WorldPath 'C:\test-world' -Mode SPEAR -Air true
tools/combat-probe/run-probe.ps1 -Name spear-high -WorldPath 'C:\test-world' -Mode SPEAR -High true
tools/combat-probe/run-probe.ps1 -Name armor-lifecycle -WorldPath 'C:\test-world' -Mode ARMOR
tools/combat-probe/run-probe.ps1 -Name defense-world -WorldPath 'C:\test-world' -Mode DEFENSE
```

每次只运行一个实例，使用新的 Name。脚本复制整个世界到本目录的 runs，**不修改原世界**。运行约 800 个客户端 tick 后自动退出。测试实例不带上传凭据，无账号凭据写入。若环境尚未完成 Minecraft 首次引导，需要先在测试窗口完成引导。先单独构建仅含测试代码的 fixture Jar，再以显式开发参数启动，遵守开发构建禁止 Jar 打包的保护规则。

场地为 y=100 的石头平台。初始化会在复制的世界中清除已加载的生物、关闭自然刷怪，并断言追击目标 UUID 与被计量目标一致，防止上次存档遗留目标污染结果。固定目标为 1000 生命、无 AI、无击退的僵尸，戴头盔防止日晒，武器无附魔；客户端 SpeedBps/ClimbBps 为 40、重锤高度默认 10、AutoMaceDive 和旧额外普攻选项开启、AdaptiveCombat 关闭。开启 Air 时，服务器移动目标沿半径 12 格的圆周绕行，同时上下变化 5 格，名义水平速度约 24 格/秒；它是可复现的移动压力场景，**不等同真人 PvP、玩家飞行物理或反作弊验收**。

新增地面与装备回归参数：`-Ground true` 从双方同一地面起步；`-Gear INVENTORY` 将胸甲放在主背包；`-Gear EMPTY` 不提供胸甲；`-Gear EMPTY_INVENTORY` 填满快捷栏，仅主背包有空槽。`-Height 28`、`-Terrain false` 对应高下砸高度和关闭地形保护。`-Ticks 600 -RequireDamage true` 明确要求服务器确认伤害，重锤必须至少出现一次 `mace_smash`。结束后验证服务器胸部恢复鞘翅、胸甲回原槽、鼠标无残留物品，并记录 `PROBE_RECOVERY`。`groundTicks/maxGroundStreak` 统计起步 40 tick 后触地时间。

`-Mode UI` 验证默认已开启模块列表、旧手动名单、TP 伤害选项及过期清理，并将 TP 床的四个方向与原版真实床形状边界比较。它调用真实模块渲染入口保存四张 `combat-ui-*.png`，结果为 `ui-complete.txt`；固定显示快照用于视觉检查，不把它作为自动选敌/爆炸命中的证据。

ARMOR 场景关闭追人和方向控制，单独启用 ArmorFly，模拟一次手动起飞，随后由模块自行补烟花。第 200–220 tick 借用/归还飞行控制，断言独立甲飞仍启用；第 400 tick 关闭甲飞，第 410 tick 断言装备恢复。

DEFENSE 场景逐 tick 比较原版珍珠与共享预测器的干燥地面轨迹，并在真实 ClientLevel 中注入测试玩家/珍珠，验证渲染关闭时的落点威胁、航向保持、障碍打断、自己/未知所有者/好友排除、预测自己已远离、功能开关和停用清理。完成标志为 `defense-complete.txt`，失败文件会使启动脚本失败。客户端注入实体只证明功能接线，不代表真人 PvP、服务端伤害或真实网络延迟；检测耗时包含反射调用，仅供该场景参考。

## 结果

- 日志：本目录 `<Name>.log`，服务器实际扣血为 `PROBE_DAMAGE`，区分 `mace_smash` 和 `spear`。
- 每 5 tick 采样：`runs/<Name>/combat-trace.csv`。
- 生命扣减会累计后补满，避免目标死亡缩短后续观测；不能把扣血次数称为命中率，分母不是攻击尝试次数。
- 确認 `PROBE_DONE`，并核对 `PROBE_STATE` 的目标确为测试僵尸。旧世界中的其他怪物可能造成目标污染；此时不得把该段作为有效目标证据。
- `.fakeplayer` 是客户端本地实体，服务器没有它，不能用它验证伤害。

开发启动豁免授权是项目既有行为；此测试不验证生产授权，不替代最终混淆包的双 JDK / GUI 启动验收。
