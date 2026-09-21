# RC22 放置与挖掘视觉

模块设置的 Render 页统一控制世界标记。默认只显示紧凑数字，关闭自伤、阶段、时间预测和文字背景。覆盖 AutoCrystal、AutoAnchor、PistonCrystal、AutoBed（含 TP 模式）、TP 水晶、PacketMine、AutoMine 及其补玻璃标记。

## 显示内容

- 水晶文字固定在底座内部，锚文字固定在锚体内部，床文字固定在两格的中点；活塞和红石文字也在各自方块内。采用朝向相机的 3D 文字，按世界坐标和透视缩放，不外移、不画引线、不生成悬浮信息牌。
- 水晶与锚当前决策值是多个目标的合计，数字表示合计预测伤害；床、TP 水晶和活塞水晶是对应目标的预测伤害。活塞使用推动后位置估算。均为客户端预测，不是服务器实际扣血。
- 自伤来自已有决策计算。没有有效估算的预放动作隐藏伤害数值，不显示虚构的 0。
- 锚显示充能数及等待放置／充能／引爆确认。提交发包不等同于服务器确认。
- 活塞底座默认显示主要伤害；开启 StageText 后显示 BASE／PISTON／CRYSTAL／POWER／PUSH／HIT／WAIT 等执行阶段。活塞和红石有独立颜色、部件文字与推动方向箭头：P? 待放活塞，P+ 已就位，R= 原有红石复用，R? 新放待确认，R+ 已就位。
- PacketMine 显示主副挖、真实进度百分比、预计剩余秒数、暂停／等待方块消失。进度达到 100% 不代表服务器已挖除；暂停时隐藏时间预测。
- AutoMine 显示目标用途及是否交给 PacketMine。可选 SC 数字是选点评分，不是伤害。补玻璃标记显示提交及等待挖除状态。
- 同一方块上的多个模块合并紧凑文字，只有合并时才加 C（水晶）、A（锚）、PC（活塞水晶）、B（床）、TC（TP 水晶）、PM（发包挖）、AM（自动挖）、G（补玻璃）前缀。相邻投影重叠时先收起次要行，再隐藏后排标签；绝不把文字挪到方块外面。

## Render 选项

| 选项 | 含义 |
| --- | --- |
| RenderStyle | BOX 框与填充 / FILL 填充 / OUTLINE 边框 / GRADIENT 渐变 / PULSE 脉冲。水晶沿用原 Render 枚举和旧配置键。 |
| Occlusion | THROUGH 穿墙 / VISIBLE_ONLY 仅可见 / FADED 墙后淡显，默认 FADED。几何使用深度测试，文字按视线遮挡判断。 |
| OccludedAlpha | 墙后透明度倍率，默认 0.25。 |
| HoldTime / FadeTime | 保持时间与后续连续淡出秒数；水晶保留原 RenderTime（tick 单位）。 |
| Smooth / Smoothness | 按实际经过时间平滑，独立于 FPS；水晶平滑由原有样式控制。PacketMine 对进度只平滑一次。 |
| Info | 信息文字总开关。 |
| DamageText / SelfDamageText | 预计伤害（默认开）与自伤（默认关），挖掘模块不显示这些选项。 |
| StageText / DistanceText | 阶段／距相机距离，默认关闭；S 为自伤，Q 为锚充能数，PL／CHG／DET 为放置／充能／引爆，WAIT 为等待确认。 |
| TextScale / TextDistance | 世界文字大小／最大可见距离；长文本自动限制在方块内部。 |
| TextBackground / TextColor | 可选世界文字背景（默认关闭）与文字颜色。 |
| PartLabels / PushArrow | 活塞部件文字／推动箭头。 |
| ProgressText / RemainingText / ProgressBox | PacketMine 百分比／时间估计／随进度扩张的框。 |
| TargetRender / ScoreText | AutoMine 目标框／选点策略分。目标类型缩写 FT 脚部、SR 包围、EX 延伸、FP 贴身、HD 头部、BL 脚下、BS 地形底座。 |

帧内文字队列上限 64、每个模块残留标记上限 16；切换世界或玩家会清空。床采用两格、9/16 方块高的模型轮廓；水晶各样式统一使用底座坐标。关闭模块立即清理，完成动作的标记按时间淡出。

## 验证范围

`placementVisualTest` 覆盖不同 FPS 的平滑一致性、淡出边界、四个方向的床轮廓及文字在方块内部的尺寸限制和投影重叠判断。
独立 Fabric Client GameTest 世界验证共用渲染器、遮挡、文字合并、渐变、脉冲及到期清理。测试场景中的伤害和进度为合成数据，用于视觉验收，不是实战伤害测试；测试夹具不进入发行包。


## 源码对照

只读研究以下源码的渲染入口；未运行或验收它们各自的构建。当前实现适配 Minecraft 1.21.11，使用原版 Gizmos.billboardText 的世界文字路径。

- Alien：提交 54f483341210679809c71b17896eaf5a44501735，gradle.properties 为 MC 1.21.1。Render3DUtil.drawText3D 使用相机朝向与 0.025 世界缩放；AutoCrystal.CrystalRender 使用晶体下方底座中心与紧凑伤害；PacketMine 使用方块中心的百分比／Waiting。AutoAnchor.AnchorRender 只有框，未实现伤害文字。
- NullPoint：dyyibang/NullPoint-Client 提交 01ef4ac7bb4301f09d9cff7f9cbdce37b2ca5651，MC 1.20.4。Render3DUtil.drawText3D 同样按世界坐标及相机旋转绘制；AutoCrystal.CrystalRender 将两位小数伤害绘制在底座中心，SpeedMine 将百分比绘制在方块中心。AutoAnchor.AnchorRender 只有框。
- 两者水晶／锚的部分 AnimateUtil.animate 调用使用每帧固定比例，不能替代本次基于单调时钟的平滑；它们的穿墙开关方式也需适配现代渲染管线。保留本次的三种遮挡策略、实际经过时间的平滑和淡出。

源码：
[Alien Render3DUtil](https://github.com/14600hk/AlienV4EZLEAKGZ_SakuraLLL/blob/54f483341210679809c71b17896eaf5a44501735/src/main/java/dev/luminous/api/utils/render/Render3DUtil.java#L62)
[Alien AutoCrystal](https://github.com/14600hk/AlienV4EZLEAKGZ_SakuraLLL/blob/54f483341210679809c71b17896eaf5a44501735/src/main/java/dev/luminous/mod/modules/impl/combat/AutoCrystal.java#L1145)
[Alien PacketMine](https://github.com/14600hk/AlienV4EZLEAKGZ_SakuraLLL/blob/54f483341210679809c71b17896eaf5a44501735/src/main/java/dev/luminous/mod/modules/impl/player/PacketMine.java#L748)
[NullPoint Render3DUtil](https://github.com/dyyibang/NullPoint-Client/blob/01ef4ac7bb4301f09d9cff7f9cbdce37b2ca5651/src/main/java/me/nullpoint/api/utils/render/Render3DUtil.java#L53)
[NullPoint AutoCrystal](https://github.com/dyyibang/NullPoint-Client/blob/01ef4ac7bb4301f09d9cff7f9cbdce37b2ca5651/src/main/java/me/nullpoint/mod/modules/impl/combat/AutoCrystal.java#L697)
