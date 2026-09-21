# RC22 放置与挖掘视觉

模块设置的 Render 页统一控制世界标记。覆盖 AutoCrystal、AutoAnchor、PistonCrystal、AutoBed（含 TP 模式）、TP 水晶、PacketMine、AutoMine 及其补玻璃标记。

## 显示内容

- 水晶文字固定在底座，锚文字固定在锚体中心；床文字位于两格中点上方。空间拥挤时允许移位，并用引线指向原位置。
- 水晶与锚当前决策值是多个目标的合计，因此显示“预计总伤”；床、TP 水晶和活塞水晶显示“预计伤害”。活塞标明“推动后估算”。均为客户端预测，不是服务器实际扣血。
- 自伤来自已有决策计算。没有有效估算的预放动作隐藏伤害数值，不显示虚构的 0。
- 锚显示充能数及等待放置／充能／引爆确认。提交发包不等同于服务器确认。
- 活塞底座显示主要伤害、底座／水晶状态及执行阶段；活塞和红石有独立颜色、部件文字与推动方向箭头。红石区分现有复用、新放待确认及已就位。
- PacketMine 显示主副挖、真实进度百分比、预计剩余秒数、暂停／等待方块消失。进度达到 100% 不代表服务器已挖除；暂停时隐藏时间预测。
- AutoMine 显示目标用途及是否交给 PacketMine。可选“策略分”是选点评分，不是伤害。补玻璃标记显示提交及等待挖除状态。
- 同一方块上的多个模块合并面板，并保留模块名称；相邻标记统一分配屏幕位置。拥挤时先收起次要行，空间不足再隐藏后排标签，避免文字叠写。

## Render 选项

| 选项 | 含义 |
| --- | --- |
| RenderStyle | BOX 框与填充 / FILL 填充 / OUTLINE 边框 / GRADIENT 渐变 / PULSE 脉冲。水晶沿用原 Render 枚举和旧配置键。 |
| Occlusion | THROUGH 穿墙 / VISIBLE_ONLY 仅可见 / FADED 墙后淡显，默认 FADED。几何使用深度测试，文字按视线遮挡判断。 |
| OccludedAlpha | 墙后透明度倍率，默认 0.25。 |
| HoldTime / FadeTime | 保持时间与后续连续淡出秒数；水晶保留原 RenderTime（tick 单位）。 |
| Smooth / Smoothness | 按实际经过时间平滑，独立于 FPS；水晶平滑由原有样式控制。PacketMine 对进度只平滑一次。 |
| Info | 信息文字总开关。 |
| DamageText / SelfDamageText | 预计伤害与自伤，挖掘模块不显示这些选项。 |
| StageText / DistanceText | 阶段／距相机距离。 |
| TextScale / TextDistance | 文字大小／最大可见距离。 |
| TextBackground / TextColor | 背景与文字颜色。 |
| PartLabels / PushArrow | 活塞部件文字／推动箭头。 |
| ProgressText / RemainingText / ProgressBox | PacketMine 百分比／时间估计／随进度扩张的框。 |
| TargetRender / ScoreText | AutoMine 目标框／选点策略分。 |

帧内文字队列上限 64、每个模块残留标记上限 16；切换世界或玩家会清空。床采用两格、9/16 方块高的模型轮廓；水晶各样式统一使用底座坐标。关闭模块立即清理，完成动作的标记按时间淡出。

## 验证范围

`placementVisualTest` 覆盖不同 FPS 的平滑一致性、淡出边界、四个方向的床轮廓及拥挤屏幕下的文字布局。
独立 Fabric Client GameTest 世界验证共用渲染器、遮挡、文字合并、渐变、脉冲及到期清理。测试场景中的伤害和进度为合成数据，用于视觉验收，不是实战伤害测试；测试夹具不进入发行包。
