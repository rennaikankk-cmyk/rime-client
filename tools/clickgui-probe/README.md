# ClickGUI 实机检查

独立测试模组，仅通过现有 `-PgzwDevelopmentBuild=true` 开发门运行，不会进入客户端发行包。测试不连接服务器、不读取生产凭据。

在仓库同级新建 `clickgui-lab/<run>/`；`options.txt` 使用 `guiScale:2`、`pauseOnLostFocus:false`。可将已有合法字体放在该实例的 `guizhouware/fonts/`，缺失时沿用客户端字体回退。

```powershell
$env:JAVA_HOME='C:/path/to/jdk-21'
.\gradlew.bat --offline -I tools/clickgui-probe/probe.gradle clickGuiProbeJar
.\gradlew.bat --offline -PgzwDevelopmentBuild=true -I tools/clickgui-probe/probe.gradle -PprobeRun=<run> runClient
```

通过标准：存在 `complete.txt`、不存在 `failure.txt`，并人工检查 `screenshots/` 中七张真实渲染图，覆盖中英文、搜索展开设置、分类列、布局恢复与 GUI 缩放。探针检查面板边界、工具栏搜索区分离、模式切换的保存位置、标题折叠、拖动、一键整理和 Ctrl+F/Escape。

注意：Minecraft 会限制 GUI 缩放的最大值，不同请求可能得到相同实际 GUI 大小；不能把这些截图当成七种不同窗口尺寸。布局单元测试另覆盖 320×240 至 1920×1080 的六种逻辑视口。
