# 水晶、TP 水晶与自动挖掘联动实机回归

使用真实 Minecraft 1.21.11 客户端和原版集成服，复制测试世界，启用生产模块并等待正常 tick。测试不注入候选位置、目标缓存或调用私有放置/攻击方法。

```powershell
tools/crystal-auto-probe/run-probe.ps1 -Name verified -WorldPath 'C:\test-world' -JavaHome 'C:\jdk-21'
```

19 个场景覆盖：

- 普通水晶、TP 水晶各自 Rotate 开/关；普通攻击距离恰好为 3 格。
- 最优底座被掉落物占据时选择另一个可执行底座。
- 虚弱 I、主手金苹果且快捷栏有剑；自动换武器攻击后还原。
- 副手水晶、无水晶、自伤上限为零。
- AutoMine + PacketMine + 普通水晶自动挖开目标脚边石头、保留黑曜石底座、放晶、引爆。
- 自动挖掘补晶遵循水晶 MinDamage、BreakRange、PlaceDelay。

驱动玩家使用生存模式、保护 IV 下界合金甲，无抗性效果。客户端 RemotePlayer 提供玩家专用选择器目标；同位置的服务端僵尸拥有 1000 HP、无 AI/重力/击退、无护甲，用于确认实际爆炸掉血。客户端代理不会替代服务端伤害记录。该测试不代表真人 PvP 胜率，也不验证长距离 TP、Via 或 Grim 接受情况。

结果写到仓库同级 `crystal-auto-lab/<Name>/results.json`。验收同时要求：客户端进程成功退出、`complete.txt` 存在、`failure.txt` 不存在、所有场景 `success=true`。记录本地接受的 use/attack/STOP 包、服务端实际水晶、挖掘后的空气、爆炸伤害和首个放置时间；本地接受数据包不等于服务端确认。

`-Baseline` 允许旧版失败场景写出完整结果；不能当成候选通过。Gradle 的 `-PprobeStart=N -PprobeEnd=M` 可单独调试，最终验收运行全部场景。测试 Jar 单独构建，仅包含夹具；客户端沿用项目既有显式开发运行入口，不能以开发标志打包生产客户端。
