# 字体实机检查

独立测试模组，仅用于显式开发启动，不包含在发行客户端内。创建新的仓库同级 `font-audit/<Name>/`，把测试世界副本放在 `saves/ProbeWorld/`，并将本机字体放在 `guizhouware/fonts/`：

- `anthropic-serif-regular.ttf`
- `anthropic-serif-semibold.ttf`
- `anthropic-serif-bold.ttf`

三个文件也支持 `.otf`。建议测试实例 `options.txt` 设置 `guiScale:2`、`pauseOnLostFocus:false`、`renderDistance:4`。字体文件不进入源码仓库。

```powershell
.\gradlew.bat --offline --console=plain -I tools/font-probe/probe.gradle fontProbeJar
.\gradlew.bat --offline --console=plain -PgzwDevelopmentBuild=true -I tools/font-probe/probe.gradle -PprobeRun=<Name> runClient
```

测试必须生成 `complete.txt` 和 `screenshots/font-audit.png`，不得存在 `failure.txt`。它核实实际选中字形家族、三个字重、Nametag/通用标签宽度一致、自定义后端没有退回原版；截图包含真实 HUD 字体绘制、Nametag 模块、中文回退和物品数量。人工检查截图以确认可读性和对齐。

生产字体从当前游戏实例目录读取；缺少本机字体时按已安装的 Anthropic Serif/Anthropic Serif Web、Source Serif 4、Noto Serif SC、Palatino、宋体等回退。日志会记录实际选择的字体名。`guizhouware.vanillaFonts=true` 及后端失败时的原版兼容路径仍保留。
