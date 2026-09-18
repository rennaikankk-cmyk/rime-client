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
```

每次只运行一个实例，使用新的 Name。脚本复制整个世界到本目录的 runs，**不修改原世界**。运行约 800 个客户端 tick 后自动退出。客户端遥测关闭，无账号凭据写入。若环境尚未完成 Minecraft 首次引导，需要先在测试窗口完成引导。

场地为 y=100 的石头平台。初始化会在复制的世界中清除已加载的生物、关闭自然刷怪，并断言追击目标 UUID 与被计量目标一致，防止上次存档遗留目标污染结果。固定目标为 1000 生命、无 AI、无击退的僵尸，戴头盔防止日晒，武器无附魔；客户端速度设为 3.1、重锤高度 10、AutoMaceDive 开启、额外普通攻击关闭、AdaptiveCombat 关闭。开启 Air 时，服务器移动目标沿半径 12 格的圆周绕行，同时上下变化 5 格，名义水平速度约 24 格/秒；它是可复现的移动压力场景，**不等同真人 PvP、玩家飞行物理或反作弊验收**。

ARMOR 场景关闭追人和方向控制，单独启用 ArmorFly，模拟一次手动起飞，随后由模块自行补烟花。第 200–220 tick 借用/归还飞行控制，断言独立甲飞仍启用；第 400 tick 关闭甲飞，第 410 tick 断言装备恢复。

## 结果

- 日志：本目录 `<Name>.log`，服务器实际扣血为 `PROBE_DAMAGE`，区分 `mace_smash` 和 `spear`。
- 每 5 tick 采样：`runs/<Name>/combat-trace.csv`。
- 生命扣减会累计后补满，避免目标死亡缩短后续观测；不能把扣血次数称为命中率，分母不是攻击尝试次数。
- 确認 `PROBE_DONE`，并核对 `PROBE_STATE` 的目标确为测试僵尸。旧世界中的其他怪物可能造成目标污染；此时不得把该段作为有效目标证据。
- `.fakeplayer` 是客户端本地实体，服务器没有它，不能用它验证伤害。

开发启动豁免授权是项目既有行为；此测试不验证生产授权，不替代最终混淆包的双 JDK / GUI 启动验收。
