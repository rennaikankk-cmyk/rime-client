# Anthropic Serif 字体遗漏修复

检查确认原有两条字体链并不一致：菜单/HUD 仅按系统安装名称查找 Anthropic Serif，而 Nametag 与世界标签优先使用 `ib_latin.ttf`。该文件的真实字形名称为 Cormorant Garamond Light Regular；当前系统没有安装 Anthropic Serif，所以菜单/HUD 也实际使用回退字体。

在用户本机 Claude 桌面端 2.9939.2 的 `resources/ion-dist/_frame-rt/_runtime/` 中找到 `AnthropicSerif-Roman-Web.e96fe97bb7ba2190.woff2`。内部字体家族为 Anthropic Serif Web，含 wght 300–800、opsz 16–48 两个可变轴，不含中文字形。为本机生成 400、600、700 字重的静态 TTF（opsz=16），放入当前 1.21.11 Fabric 实例的 `guizhouware/fonts/`。字体文件不加入源码仓库或客户端 JAR；登录器 1.0.1 从官网独立下载三个字重及中文回退。

本次改动：

- 菜单、HUD、Nametag、世界标签共享 FontFaces 字体链，支持实例目录中的 TTF/OTF 和实际安装的 Anthropic Serif Web 家族名。
- Nametag 的名称、距离、爆图腾次数及物品数量统一；保留显式 NametagFont 开关。
- 修复潜影盒预览物品数量、挖掘提示、水晶伤害数字、移动调试文本的原版字体残留。
- 客户端命令补全与用法提示使用相同字形和宽度测量；补全面板点击区域同步按新字体宽度调整。原版聊天编辑框仍按原版输入/光标规则工作。
- 保留中文回退、物品耐久条和冷却覆盖，以及字体后端失败时的原版兼容路径。第三方 Rusher 客户端界面未改。

验证：86 个客户端回归任务通过，包括新字体选择/损坏文件/缓存隔离测试，以及既有字体恢复测试。独立 Minecraft 1.21.11 开发实例实际加载三个 Anthropic Serif Web 字重，确认 Nametag/通用标签宽度一致、自定义后端未回退，并检查了包含真实 Nametag 与物品数量的截图。字体探针源码位于 `tools/font-probe/`；本机结果位于仓库同级 `font-audit/render01/`。

以上修复已推送 main，并随 Rime 1.1.2 / Rimeguard 3.1 正式发布；平地水晶底座修复同时包含在发行包中。
