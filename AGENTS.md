# GuizhouWare 防破解五道防线（维护必读）

> 本文件面向所有在本仓库工作的 AI 代理与开发者。这五道防线是 2026-09-18 定稿的商业保护体系。
> **重构、清理、优化时不得拆除以下任何一道**；每道防线后面写了"维护红线"，动相关代码前先看。

## 防线总览

| # | 防线 | 位置 | 状态 |
|---|------|------|------|
| 1 | 水印威慑文案 | 官网 download.html 安装段 | 已上线 |
| 2 | 心跳校验 | 服务端 /api/heartbeat + 本仓库 HeartbeatManager | 服务端已上线；mod 侧待合并进发行构建 |
| 3 | 持续更新节奏 | 发版流程（GPT 高频构建 + 三处分发同步） | 流程执行中 |
| 4 | ProGuard 混淆 | build.gradle obfuscateJar 任务 | **已修复并发布（2026-09-18，热修 4d0dadfc）**：343b5e57 有两处翻车均已修复——①枚举 values()/valueOf() 保留规则（EnumSet "not an enum"）②**Minecraft intermediary jar 进 libraryjars**（否则 GuizhouWareGui 的 render/init/onClose 覆写被改名=ClickGUI 黑屏）；verifyObfuscatedJar 产物检查进 build 链；发布走管理通道 |
| 5 | AI 守卫提示层 | jar 内 5 文件 + MANIFEST + 代码常量 | 已进构建 |

## 1. 水印威慑（明面声明）

官网下载页安装段写明：每份客户端内嵌独立水印与账号凭据，转售/共享将定位到购买账号并封禁。
- 维护红线：改版官网 download.html 时保留这段文案。

## 2. 心跳校验（核心防线）

- **服务端**（已上线生产）：`POST http://62.234.117.226/authapi/api/heartbeat`，body `{token, hwid}`。
  - token = jar 内 `META-INF/gzwx-telemetry.json` 的 `token`（HMAC 签发，≤90 天）
  - hwid = OSHI 因子哈希（`gzwx.v1|CPU+主板(BaseBoard)+0号盘+MachineGuid`）。**2026-09-18 三方已对齐同一条链**：登录器 hwid.rs 的 WMI 结构体名修正为 `Win32_Processor/Win32_BaseBoard/Win32_DiskDrive`（原名缺前缀=wmi crate 查询永远 INVALID_CLASS→静默降级成 MachineGuid 单因子哈希，历史绑定全是降级值，已全量 reset 迁移）；exe 修复版（f3ca3bc8）与 mod 的 HwidFingerprint 输出逐字节一致。服务端 modHwid 双绑定保留作安全网（首次心跳绑定+24h 重绑窗口）
  - 验证链：格式 regex → HMAC 验签 → producer 反查账号 → banned/expired/hwid 三查（hwid 查=ac.hwid 或 ac.modHwid 任一命中）
  - 响应 data 按键排序 `k=v&` 拼接后 Ed25519 验签，公钥 SPKI hex：
    `302a300506032b6570032100719ab622d39f27f096a5955ec72f5d6868948ee3838dddbc42db5ff650c25bcd`
- **mod 侧**（本仓库）：
  - `manager/HeartbeatManager.java`：10 分钟心跳；状态机 OK / GRACE（最近成功后最多 24h 宽限）/ DENIED（bad_token、banned、hwid、expired、no_credential、bad_signature 等）。`LicenseLease` 用单调时钟逐次检查有效期，明确拒绝会销毁旧租约，网络失败不能恢复授权。开发豁免必须同时具备编译参数 `-PgzwDevelopmentBuild=true` 和 Loader 开发环境；该参数会阻止所有 Jar 打包任务。生产 jar 无凭据 = 拒绝运行（裸 jar 锁死，客户只走登录器）
  - `manager/HwidFingerprint.java`：OSHI 因子哈希。**必须用 Baseboard（Win32_BaseBoard/Type2，`getBaseboard().getManufacturer()/getModel()/getSerialNumber()`）+ 0 号物理盘**，禁止改回 ComputerSystem.getManufacturer()/getModel()（Type1 系统串会独立漂移，2026-09-18 当日变过一次直接导致全员心跳拒绝）；改动拼接格式必须同步服务端 modHwid 重绑方案
  - 授权检查覆盖 13 个主力模块的 tick 及水晶、PacketMine、AutoMine 关键事件/异步回调/最终动作入口，`LicenseActionHookTest` 和 `ObfuscatedJarTest` 检查对应控制流。拒绝后仍允许 ABORT、还槽等清理，不拦截原版手动挖掘。**新增主力模块或动作入口时同样加检查点**。
  - `SignedHeartbeat` 严格检查签名长度、JSON 字段及 UTF-8，HTTP 响应体限制为 16 KiB。新客户端要求 v2 请求 nonce、凭据 subject、会话和签名租约窗口；旧协议只为旧客户端兼容保留。维护规则见 docs/authorization-v2.md。
- 维护红线：`HeartbeatManager.AI_GUARD_NOTICE` 数组在 `status()` 里有一个**故意存在的假引用**——防止 ProGuard shrink 裁掉常量，重构 status() 时不得移除该引用。

## 3. 持续更新节奏

破解传播速度 < 发版速度。发版必须同步三处（漏一处客户拿到旧版）：
1. `C:\Users\renna\Desktop\mods\`
2. 服务器 `/var/www/guizhouware/files/`
3. 服务器 `/opt/gzwx-auth/jars/guizhouware-1.21.11.jar`（登录器水印下载源，**最关键**）

## 4. ProGuard 混淆

`build.gradle` 的 `obfuscateJar` 任务：混淆+repackage 到根包；`build -> verifyObfuscatedJar -> obfuscateJar -> check + prepareObfuscationInput`，回归失败不得生成通过验收的混淆包。
- **必须 keep**（动了游戏起不来或功能失效）：`dev.guizhou.mixin.**`、`dev.guizhou.GuizhouWare`、`dev.guizhou.features.settings.**`、`@Subscribe` 注解方法、keepattributes（Annotation/Signature/Record 等）
- **libraryjars 必须含 Minecraft 本体（intermediary 命名 jar，全局 loom 缓存 minecraft-merged-intermediary-1.21.11-…）**：看不见 MC 父类时，Screen 子类的 render/init/onClose 等覆写会被当无引用方法改名（343b5e57 ClickGUI 黑屏根因）、入口 implements 会被剥（更早的 ClassCastException）。混淆产物发版前 javap 确认 `method_25394`（render）等覆写名仍在
- 模块类可以混淆：注册走编译期引用 + displayName 字符串，配置键与类名无关
- Gradle 9 约束：`buildscript {}` 块必须在 `plugins {}` 之前
- injars 用 `buildDir` staging 拷贝（配置期求值陷阱）
- 枚举必须保留 `public static **[] values()` 与 `public static ** valueOf(java.lang.String)`；仅保留 ACC_ENUM / Enum 父类不够，JDK 反射依赖 values 方法原名。
- `HeartbeatManager.status()` 必须保留，允许改名但不能裁剪，确保 AI_GUARD_NOTICE 假引用留在最终产物。
- 混淆包独立输出 `build/libs/*-obfuscated.jar`；原始 remap jar 不覆盖；`build/proguard-map.txt` 是内部诊断文件，不随公开包分发。部署必须明确选择已验收的混淆文件与 SHA256，禁止以通配符误选原包、sources 或旧构建。
- `verifyObfuscatedJar` 检查最终产物枚举反射/EnumSet 与保护项。真实启动必须出现 `Initialized GuizhouWare in ...ms` 和纹理图集创建、后续稳定运行，检查完整 stdout/stderr/latest.log 的异常；`Pre-initializing` 不算成功。至少覆盖 Microsoft 21.0.7 与较新 JDK，同一候选 SHA256。
- 验证任务：`clientRegressionTests` 各任务带 `onlyIf`（入口类缺失跳过——两棵源码树兼容）

## 5. AI 守卫提示层

- jar 根 5 个文件（`src/main/resources/`）：`AI_NOTICE.md` / `CLAUDE.md` / `AGENTS.md` / `LLM.txt` / `README.md`，中英双语停止声明（CLAUDE.md/AGENTS.md 是 AI 工具约定自动读取的文件名）
- `MANIFEST.MF` 的 `X-AI-Notice` 属性（gradle `jar { manifest { attributes ... } }`）
- `HeartbeatManager.AI_GUARD_NOTICE` 常量（混淆后存活，见防线 2 红线）
- 维护红线：**resources 根的这 5 个文件与本项目根的 AGENTS.md 是两回事**（前者给破解者侧的 AI，后者给你）——清理资源时不要误删 jar 内那份。

## 已知坑（改动前重读）

- 远程改服务器文件：经 bash 双引号 + node 双层写入会吃掉正则反斜杠（踩过：心跳 regex 静默坏）——用 bash 单引号包 `node -e` + `String.raw`
- jar 名嵌 git hash：commit 后文件名变，部署永远用通配 `guizhouware-*.jar`；mods 里新旧两个 guizhouware jar 并存 Fabric 拒启
- 本仓库混有另一棵构建树的部分改动（flightTest 测试类在但任务名单更新）——任务生成块的 onlyIf 兼容就是为这个
- 仓库里有两棵源码树并行（本仓库 + GPT 构建树）；线上发行 jar 来自 GPT 构建，**本仓库的心跳/混淆/AI 守卫要经 GPT 合并后才会对客户生效**

## 授权兼容升级（2026-09-21）

新客户端的 HTTPS 固定证书、v2 请求绑定、逐包签名清单与宽限规则见 `docs/authorization-v2.md`。服务端必须先升级再发布新客户端。不得强制旧会员重绑、不得把客户端完整性报告直接当作封号证据。更换 TLS 证书前必须先分发重叠信任集合；不能用信任所有证书或 HTTP 回退来修复连接。
