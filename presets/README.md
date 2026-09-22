# 飞行预设

将预设文件夹复制到游戏 `guizhouware/configs/`，先保存当前配置，再执行 `.configs load <名称>`。

- `manual-coupled-cruise`：Grim，手动飞行，开启地形防护，关闭 AutoFly、追击和独立甲飞。加载后手动开启飞行。
- `vanilla-mace`、`vanilla-mace-rockets`、`vanilla-mace-height20`：保留原追击参数，飞行统一迁移为 NoAC，水平和拉升各 120 格/秒。目录名保留，兼容原有加载习惯；名称中的 rockets 不再表示额外烟花飞行模式。

这些是部分配置，未指定的其他模块仍沿用当前设置。新版只提供 Grim / NoAC，历史版本的对打记录不能作为本次飞行改造后的胜率验收。需要甲飞时单独启用 ArmorFly。

控制和实测边界见 [鞘翅飞行说明](../docs/coupled-cruise.md)。
