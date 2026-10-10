# Claude 视觉交接

把这套 Claude 夜色视觉合并进更新后的客户端时，以 `preview/claude-visual.html` 为视觉规格。本文记录用户已经拍板的默认值和布局，避免新窗口把已经改掉的画法做回去。

## 1. 状态

这只是预览。Java 客户端没有改过。

- 预览文件：`preview/claude-visual.html`
- 曾经用本地静态服务打开过：`http://127.0.0.1:8765/claude-visual.html`
- 不要假定这个服务还在跑。需要看预览时，在 `preview` 目录自行起一个静态服务，或直接用浏览器打开该 HTML。
- 根节点属性就是默认状态：`data-tone="night"`、`data-lang="en"`、`data-framed="off"`、`data-face="all"`、`data-accent="one"`、`data-on="bar"`、`data-frame="glass"`、`data-desc="on"`、`data-blur="off"`。

## 2. 用户锁定的默认值

| 属性 | 默认 | 另一档 |
| --- | --- | --- |
| `data-tone` | `night`（Night / 夜色） | `paper`（Paper / 纸色） |
| `data-lang` | `en`（EN） | `zh`（中文） |
| `data-framed` | `off`（Frame / 带框 关闭） | `on` |
| `data-face` | `all`（All serif / 全部衬线） | `display`（Title serif / 标题衬线） |
| `data-accent` | `one`（One terracotta / 统一陶土） | `warm`（Warm categories / 分类暖色） |
| `data-on` | `bar`（Edge bar / 启用色条） | `ink`（Colored text / 启用着色） |

语言只存在于页面的 `data-lang`，没有 localStorage。刷新 HTML 会回到英文。

启用模块用色条，不用整段着色文字。菜单样式默认仍是玻璃列（`data-frame="glass"`）；分类列是 `list`。

## 3. 色板

夜色是要落地的主题。纸色留着可切，不是默认。

### 夜色

| 令牌 | 色值 |
| --- | --- |
| 背景 `--bg` | `#262624` |
| 侧栏 `--side` | `#1F1E1C` |
| 卡片 `--card` | `#30302E` |
| 抬起 `--raised` | `#3A3935` |
| 线 `--line` | `#45443F` |
| 字 `--ink` | `#FAF9F5` |
| 次要 `--muted` | `#C2C0B6` |
| 更淡 `--faint` | `#8A887F` |
| 强调 `--accent` | `#E08A6C` |
| 深陶土 `--accent-deep` | `#D97757` |
| 强调底 `--wash` | `#3C2E28` |
| 强调上的字 `--on-accent` | `#1C1B19` |
| 成功 `--ok` | `#9BBFA6` |
| 警告 `--warn` | `#E0B15C` |
| 危险 `--danger` | `#E39A92` |
| 世界天空 | `#3A312A` |
| 世界地面 | `#221E1A` |

### 纸色

| 令牌 | 色值 |
| --- | --- |
| 背景 | `#FAF9F5` |
| 侧栏 | `#F0EEE6` |
| 卡片 | `#FFFFFF` |
| 抬起 | `#F6F5F0` |
| 线 | `#E6E4DA` |
| 墨 | `#141413` |
| 次要 | `#5E5D59` |
| 更淡 | `#87867F` |
| 强调 | `#D97757` |
| 深陶土 | `#C15F3C` |
| 强调底 | `#F3E6DF` |
| 成功 | `#3F6B4E` |
| 警告 | `#A9782A` |
| 危险 | `#B5453A` |

### 语义色（颜色页用的是这组夜色，不要退回旧默认）

| 用途 | 色值 |
| --- | --- |
| 象牙字 | `#FAF9F5` |
| 次要 | `#C2C0B6` |
| 陶土强调 | `#E08A6C` |
| 鼠尾草（友方、基岩洞、成功） | `#9BBFA6` |
| 沙色（警告、双洞） | `#E0B15C` |
| 暖危险（敌对、黑曜石洞、下线、死亡） | `#E39A92` |
| 卡片 | `#30302E` |
| 夜色背景 | `#262624` |
| 纸色背景 | `#FAF9F5` |
| 墨 | `#141413` |

旧客户端是石墨 `#0F1117`、鸢尾 `#8B7CFF`、青 `#5CC8FF`。水晶侧面和边线旧值是紫 `rgb(130, 80, 255)`。不要把这些写回默认值。

### 颜色页每一项（英文默认名）

预览 General 旁的 Colors 表，十六进制是写死的夜色，不跟纸色切换。

| English | 中文 | 色值 |
| --- | --- | --- |
| Name | 名字 | `#FAF9F5` |
| Friend | 友方 | `#9BBFA6` |
| Enemy | 敌对 | `#E39A92` |
| Distance | 距离 | `#C2C0B6` |
| Totem pops | 图腾次数 | `#E08A6C` |
| Crystal side | 水晶侧面 | `#E08A6C` |
| Crystal line | 水晶边线 | `#E08A6C` |
| Damage number | 伤害数字 | `#FAF9F5` |
| Bedrock hole | 基岩洞 | `#9BBFA6` |
| Obsidian hole | 黑曜石洞 | `#E39A92` |
| Double hole | 双洞 | `#E0B15C` |
| Block highlight | 方块高亮 | `#E08A6C` |
| Own trajectory | 自己的轨迹 | `#FAF9F5` |
| Enemy trajectory | 敌人的轨迹 | `#E39A92` |
| Break fill | 破坏填充 | `#E08A6C` |
| Break outline | 破坏描边 | `#C2C0B6` |
| Waypoint | 路径点 | `#E08A6C` |
| Logout | 下线 | `#E39A92` |
| Shulker edge | 潜影盒边 | `#E08A6C` |
| Shulker background | 潜影盒底 | `#30302E` |
| Tab friend | Tab 友方 | `#9BBFA6` |
| Tab enemy | Tab 敌对 | `#E39A92` |
| HUD text | HUD 文字 | `#FAF9F5` |
| HUD accent | HUD 强调 | `#E08A6C` |
| Nearby self | 附近自己 | `#E08A6C` |
| Nearby friend | 附近友方 | `#9BBFA6` |
| Nearby enemy | 附近敌对 | `#E39A92` |

暖色分类（只在 `data-accent="warm"` 时用）：战斗 `#C4654A`，世界 `#6E8B74`，渲染 `#A6846A`，移动 `#C4A574`，玩家 `#8E877C`，趣味 `#A07C78`，客户端 `#6A655E`。默认是全部陶土，不要一上来就上分类色。

## 4. 字体

界面字体栈：

```text
"Anthropic Serif", "Source Serif 4", "Noto Serif SC", "Palatino Linotype", "Songti SC", serif
```

- 本机装了 Anthropic Serif 就用它。
- 没装时，拉丁用 Source Serif 4，中文用 Noto Serif SC。
- 现在客户端 HUD 字体是 Geist（`HudFonts`，`geist-400/600/700.ttf`）。合并时换成这条衬线链。
- 不要把 Anthropic Serif 的字体文件打进 jar。它是专有字体，预览只用字体族名字回退，没有内嵌该文件。
- `data-face="display"` 时界面改无衬线（Anthropic Sans / Source Sans 3 / Noto Sans SC），标题仍用衬线。默认是全部衬线。

## 5. HUD 与世界绘制

这些是用户改过的布局。合并时按这个做，不要退回「四张小卡叠在一起」或「名字下面一条进度条」。

### 状态卡

目标、速度、诊断、通知是同一张卡（预览类名 `.focus`），不是四张叠起来的卡片。

- 目标是主体：名字、距离、血条。
- 速度在这张卡的右侧：数字加一小节柱。
- 诊断是下面一行浅色字。
- 通知是卡内底部一条细状态，带陶土标记。
- 四项仍可单独隐藏。四项全关，整张卡才消失。
- Frame 不要再给这张卡套第二层框。它本身就是面板。

### 文字雷达

附近列表（Text Radar）取代单独的玩家列表。用户认为两者功能重复，只留文字雷达。

- 内容：玩家、掉落物、末影水晶、敌对生物。按距离从远到近，自己在最后。
- 种类筛选：Players / 玩家，Drops / 掉落物，Crystals / 水晶，Hostiles / 敌对生物。
- 位置：左边，状态卡下面，贴近屏幕边缘（预览约 6px）。
- 默认不带框。
- 自己 `#E08A6C`，友方 `#9BBFA6`，敌对 `#E39A92`。近距离用沙色 `#E0B15C`。

### 贴边且默认无框

- 坐标和 Ping / TPS / FPS 没有卡片底，贴 HUD 右下角（预览 `.edge-br`，约 6px）。坐标在上，延迟三项贴底边。
- 计数、图腾数量同样默认无框，Frame 打开才套卡片。
- Frame 作用对象：文字雷达、坐标、Ping/TPS/FPS、护甲、计数、图腾、路径点、下线点、名字标签。不要用它把状态卡再包一层。

### 护甲

四件要能分开认：头盔、胸甲、护腿、靴子，每件有耐久。预览里的 SVG 剪影只是占位。进游戏画的是玩家身上的原版物品图标，不要把剪影画进游戏。

### 名字标签

在世界里，浮在玩家上方，不只是一个名字。内容：护甲、名字、距离、图腾次数、副手和主手。默认无框。友方名字 `#9BBFA6`，敌对名字 `#E39A92`，距离 `#C2C0B6`，图腾次数 `#E08A6C`。

### 破坏进度

默认是方块中心一个盒子，随挖掘进度从中心向外长大，挖完铺满方块。有淡填充和描边。填充 `#E08A6C`，描边 `#C2C0B6`。

这不是方块名加一条进度条。Chinese 模式才在方块中心加两行字：上面玩家名，下面百分比。那两行也不是进度条。

### 水晶

`GuangxiCrystalModule` 默认 Render 是 `GRADIENT`，不是进度条，也不是旧紫色。

- 画在水晶下方的放置方块上。
- 顶面填充陶土，约 0.38 不透明。
- 四个侧面从陶土往下淡出，高度约 0.7 格，底部透明。
- 顶面四边和四条竖边用边线色 `#E08A6C`。
- 伤害数字浮在放置方块中心，衬线，象牙 `#FAF9F5`，向右下错 1px 的深色 `#1C1B19`。示例数字 `12.40`。
- 侧面、边线、脉冲色都从 `rgb(130, 80, 255)` 改成陶土。伤害数字从纯白改成象牙。

### 同屏还有这些

- 洞：基岩 `#9BBFA6`，黑曜石 `#E39A92`，双洞 `#E0B15C`。
- 方块高亮：陶土线框。
- 轨迹：自己的 `#FAF9F5`，敌人的 `#E39A92`，落点圈陶土。
- 脚印、路径点（陶土）、下线点（`#E39A92`）。
- 图腾粒子：陶土小点。
- 潜影盒：底 `#30302E`，边 `#E08A6C`。
- Tab：友方鼠尾草，敌对暖红。
- 床：两格长的盒子。
- 围脚：四周四格，中间空。
- 脚下网格：浅色线格。

### 同一种淡出方块框，不要再各画一套

锚、陷阱、活塞、珍珠阻挡、传送、填洞、挖掘、自动挖矿、自动剑、传送门。它们都是淡出的填充框加描边。颜色跟方块高亮同一套陶土，不要为了「每种一个颜色」退回彩虹。

### 不是覆盖层，不要当成 HUD 去重绘

夜视、无渲染、自由相机、手模、天空盒。自动马是贴一张马的贴图，不属于这套色板。

## 6. 设置行为

EN / 中文 在顶栏，也在 General 页第一项。两边同步。默认 EN。玩家名（Steve、Alex）和模块名（KillAura）是示例，不翻译。伤害数字保持 `12.40`。

其余开关：

- Night / Paper。
- All serif / Title serif。
- One terracotta / Warm categories。
- Edge bar / Colored text。
- Menu style：Glass（玻璃）和 Stack（分类列）都要留。
- Blur background、Descriptions、Smooth、Menu scale 保留。预览里模糊默认关；这是预览页的初始值，不是要求把客户端的 BlurBackground 默认改掉。合并时只改颜色和字体，原有开关的默认开关联以新树为准，除非用户另行说明。
- Frame 默认关。打开后只给第 5 节列出的裸文字和护甲套卡片。

## 7. 合并时要改的 Java

新树里类可能换了路径。按类名搜索，不要假设路径一字不差。现在这棵树里的位置如下。

| 类 | 现在的路径 | 改什么 |
| --- | --- | --- |
| `ShadcnDraw` | `src/main/java/dev/guizhou/util/render/ShadcnDraw.java` | 石墨加鸢尾常量换成夜色令牌 |
| `ChatPalette` | `src/main/java/dev/guizhou/features/commands/ui/ChatPalette.java` | 聊天色与上面的语义色一致 |
| `ClickGuiModule` | `src/main/java/dev/guizhou/features/modules/client/ClickGuiModule.java` | 主题色；设置界面要中英切换时加语言项，默认英文 |
| `HudFonts` | `src/main/java/dev/guizhou/util/render/font/HudFonts.java` | 现在是 Geist。换成第 4 节的衬线回退，不要内嵌 Anthropic Serif |
| HUD 模块 | `src/main/java/dev/guizhou/features/modules/hud/` | 状态卡合成、文字雷达取代玩家列表、坐标和延迟贴边无框 |
| `HudClientModule` | `src/main/java/dev/guizhou/features/modules/client/HudClientModule.java` | 每个元素的背景、文字、强调默认色；雷达敌友自己的颜色 |
| `NametagsModule` | `src/main/java/dev/guizhou/features/modules/render/NametagsModule.java` | 名字、友方、敌对、距离、图腾次数、背景默认色 |
| `GuangxiCrystalModule` | `src/main/java/dev/guizhou/features/modules/combat/GuangxiCrystalModule.java` | `sideColor`、`lineColor`、`pulseColor`、`damageColor` |
| `HoleESPModule` | `src/main/java/dev/guizhou/features/modules/render/HoleESPModule.java` | 基岩、黑曜石、双洞 |
| `BlockHighlightModule` | `src/main/java/dev/guizhou/features/modules/render/BlockHighlightModule.java` | 高亮色 |
| `TrajectoriesModule` | `src/main/java/dev/guizhou/features/modules/render/TrajectoriesModule.java` | 自己的轨迹、敌人的轨迹 |
| `WaypointsModule` | `src/main/java/dev/guizhou/features/modules/render/WaypointsModule.java` | 路径点。死亡和下线点用暖危险色，不要青和纯红 |
| `LogoutSpotsModule` | `src/main/java/dev/guizhou/features/modules/render/LogoutSpotsModule.java` | 下线点 |
| `BreakIndicatorsModule` | `src/main/java/dev/guizhou/features/modules/render/BreakIndicatorsModule.java` | 填充和描边。保留从中心长大的盒子，不要改成进度条 |
| `ShulkerPreviewModule` | `src/main/java/dev/guizhou/features/modules/render/ShulkerPreviewModule.java` | 边和底。边不要留 `#8B7CFF` |
| `TablistModule` | `src/main/java/dev/guizhou/features/modules/render/TablistModule.java` | 友方、敌对 |
| `BreadcrumbsModule` | `src/main/java/dev/guizhou/features/modules/render/BreadcrumbsModule.java` | 脚印 |
| `PopEffectsModule` | `src/main/java/dev/guizhou/features/modules/render/PopEffectsModule.java` | 粒子主色和强调色改陶土，不要亮黄亮绿 |
| `BootSplash` | `src/main/java/dev/guizhou/util/render/BootSplash.java` | 启动画面字标和细线 |
| 标题画面 | `src/main/java/dev/guizhou/mixin/render/gui/MixinTitleScreen.java` | 标题卡片用同一套表面 |

床、围脚、锚、陷阱等战斗模块如果自带 `renderColor` / `fillColor`，默认色同样改成陶土，不要新造一种色相。

不要动授权检查、ProGuard keep 规则、`HeartbeatManager` 里故意留着的 AI 守卫常量，也不要删 jar 资源根里那五份 AI 声明。

## 8. 范围

这份文档只交代规格。用户没有要求按文档改 Java。在用户明确说开始合并之前，不要改客户端源码。视觉以 `preview/claude-visual.html` 为准。
