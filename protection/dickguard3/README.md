# Rime 的 rimeguard3.1

上游来源：`rennaikankk-cmyk/dick-guard` 标签 `dickguard3.1`，提交 `c912156f14556e8e54a7105b395c7797fb9be6e2`。

Rime 使用的防护名称由 dickguard 改为 rimeguard；当前发行元数据为 custom.rimeguard=3.1，mod 版本为 1.1.2。客户端授权门及发布校验兼容旧 custom.dickguard 标记，新构建仅写入新名称。

保留 Rime 的 main/client 双阶段入口及逐动作授权与 guard 授权相交的检查。心跳 client=guizhouware、协议 guard=dickguard3.0 和密码学域分隔串保持兼容；这些是内部协议，不是发行品牌。既有 protection/dickguard3 与 deployment/dickguard3 目录保留，便于复现历史发布。上游仓库名称、标签及下方原文不改写。

本次同步标准 Base64 清单解码、签名 subject/license 与当前凭据绑定、每次心跳随机 guard 类抽查及环境字段长度限制；保留并扩展完整性、模拟签名心跳和双入口授权测试。字符串池由 Rime 适配的 gen_s.py 生成。

构建：主仓库完成 build 后，本目录运行 `gradlew check obfuscateJar`、`python -m unittest discover -s tests`，再以 merge.py 合入主仓库已验收的混淆 Jar。候选必须经生产水印与签名通道生成客户副本。测试需分别验证 Microsoft 21.0.7 和较新 Java 21 的真实启动。

上游说明中的 subject pin 部署针对 Dick 下载凭据；Rime 的独立心跳保持既有兼容行为，本次不声称新增 Rime 服务端降级固定。下方上游说明按原样保留。

---

# dickguard3.1

当前 guard 版本：**dickguard3.1**（以 `b58c07d` 的严格握手与签名清单加固为基础）。原客户端的 `1.10.4-L` 是 Fabric mod 的版本号，与 guard 版本分别维护。

`guard=dickguard3.0` 是服务端严格握手的协议标识，3.1 客户端继续使用它；发布版本号与协议标识分别维护。配套服务端 subject pin 补丁已部署，独立源码仓库记录该补丁。完整客户端尚未在生产环境启动验证。

Dick Client 启动器官方版的防护层。`d1.G` 在调用原客户端入口前同步验证本地完整性和 Ed25519 签名授权响应；缺少凭据、授权失败或无法验证时阻止原入口启动。

## 组成

| 类 | 职责 |
|---|---|
| `G` | Fabric 启动入口和同步授权门 |
| `E` | 授权状态、横幅、edition.txt、环境信号 |
| `B` | 心跳协议 2、nonce/session 回显校验、签名验签 |
| `V` | 签名清单全量验证与心跳前抽查；区分 mismatch 与 unavailable |
| `W` | v2.1 late-attach 环境探针，每次心跳重新探测并经 env 上报 |
| `H` | HWID |
| `S`/`K` | 构建时生成的字符串池；入口类名仅由授权响应提供 |

`W` 的 agent 类和 self-attach 属性结果只进入 `env` 字段，不触发本地封禁。Windows 上仅有 Attach Listener 线程不构成 attach 证据。探针仅能识别列入特征表的类，有漏报可能。

## 构建与测试

需要 JDK 21 和 Python 3。仓库包含 Gradle wrapper。

```powershell
python gen_s.py
.\gradlew.bat check obfuscateJar
python -m unittest discover -s tests -p 'test_*.py'
python merge.py --original path\to\original-client.jar
```

`build/guard-only.jar` 和 `build/guard-obf.jar` 只是 guard；`merge.py` 默认生成 `dist/dickclient-1.10.4.jar`，保留原作者类字节，并将 Fabric 入口改为 `d1.G`。合并后必须由分发流程重新生成逐条目完整性清单并用服务端 Ed25519 私钥签名；仓库不包含该私钥，未签名的 JAR 会被拒绝启动。合并会移除原 `main` 等其他入口，发布前应检查原 JAR 入口的必要性。

## 部署约束

当前授权端点是 HTTP。签名保护授权决定，但传输中的 token 和 HWID 不具保密性。HTTPS 迁移需与服务端协调，单独处理。

### 阶段 1 授权响应契约

**客户端要求 guard3 握手字段。** 2026-09-27 线上 `server.js` 已接入 `guard3.js`：`ok=true` 时在已签名的 `data` 对象中加入 `entryChallenge` 和 `entryCipher`，并继续回显本次 `nonce`、`session`。所有字段都是字符串，继续使用现有的 Ed25519 canonical 签名。客户端先验签、核对 nonce/session，再解密入口；缺字段或解密失败均拒绝启动。

- `entryChallenge`：服务端本次响应生成的 16 字节随机值，小写 hex（32 字符）。
- `entryCipher`：原入口类全限定名的 UTF-8 字节与 `SHA-256(UTF-8("dick-guard-entry-v1|" + session + "|" + nonce + "|" + entryChallenge))` 的前缀逐字节异或，再以无填充 base64url 编码。类名最多 32 字节。
- 服务端只在授权成功时产生这两个字段；响应签名覆盖它们。后续心跳也必须提供，因为客户端会刷新会话关联的入口材料。

CONFIRMED：原协议只有客户端生成的 `session` 回显，无法为本地静态密文提供秘密种子。阶段 1 因而把入口类名从本地池移走，由服务端在成功授权响应里发送本次会话的密文。SUSPECTED：此机制提高离线补丁成本，但已授权客户端或能观察其 HTTP 响应者仍可取得类名；它不提供长期运行时保密性。

### 阶段 2 客户端心跳契约

- `integrity` 在确认篡改时为 `mismatch`，无法验证时为 `unavailable`，正常时缺省。mismatch 优先于 unavailable。
- 请求带 `guard=dickguard3.0`、`client=superdickclient`，以启用线上 `guard3.js` 的严格握手；不带 guard 时服务端仅走兼容路径。
- `env` 继续携带 `W.probe()` 的发现；重复标签只保留一次。环境发现不会在本地触发拒绝。
- 收到签名响应中的 `entryChallenge` 后，下一次请求带 `challengeProof = SHA-256(UTF-8("dick-guard-echo-v1|" + 上次 entryChallenge + "|" + session + "|" + 本次 nonce))` 的小写 hex。首次请求缺省。服务端阶段 2b 需按 session 保存上次 challenge 才能校验。

CONFIRMED：本地 mock 服务端覆盖正常、mismatch、unavailable+env 三条路径，并解析签名响应。SUSPECTED：回带值能关联连续心跳，但它和挑战本身都可被已授权客户端计算，不构成客户端不可伪造证明；真正的授权决定仍以服务端签名、nonce/session 绑定和服务端风控为准。

### 线上 guard3 配套（服务端不在本仓库）

CONFIRMED：按 2026-09-27 线上 `server.js` 快照和 `guard3.js` 代码复核，服务端已提供 `entryChallenge`/`entryCipher`。`guard=dickguard3.0` 时启用同一 session 的 nonce 重放检查、下一拍 `challengeProof` 校验、连续 3 次 `unavailable` 的提醒以及 `env` 观察记录；`mismatch` 保持既有 integrityBan。已用取自线上 `guard3.js` 的本地副本通过协议链测试，尚未把本分支作为完整客户端在生产环境启动验证。

完整性清单外层使用标准 Base64，条目名使用无填充 base64url；客户端分别按对应格式解码。已签名的 `subject` 和 `license` 头与本地 token、水印账号绑定；每次心跳的抽查覆盖 `d1.G`、`fabric.mod.json` 及随机一个其他 guard 类。CONFIRMED：测试签名 JAR 覆盖这些路径。SUSPECTED：抽查只能提高发现机会，完整保护仍依赖周期性全量校验和服务端策略。

## 授权态调用点清单（阶段 1 复核锚点）

| 消费位置 | 读取的状态 | 用途 |
|---|---|---|
| `G.onInitializeClient()` | `E.state`、`E.tampered`、`E.integrityUnavailable`、`V.lastResult` | 启动前同步门；只在完整性和签名授权均通过时调用原入口 |
| `E.start()` 心跳循环 | `E.state`、`E.reason` | 缺少凭据时跳过无意义心跳 |
| `E.netFail()` | `E.state`、`E.lastOkMs`、`E.graceMs` | 判定网络故障是否进入宽限期 |
| `E.confirm()` | `E.tampered`、`E.integrityUnavailable`、`V.lastResult` | 仅在完整性有效时写 edition.txt |
| `E.official()` | `E.state`、`E.tampered`、`E.integrityUnavailable`、`V.lastResult`、`G.g0`、`E.trial` | 官方版标记的统一判断 |
| `E.bannerPoll()` | `E.state`、`E.official()`、`E.tampered`、`E.integrityUnavailable` | 决定聊天横幅内容；显示前抽查完整性 |
| `B.requestBody()` | `E.tampered`、`E.integrityUnavailable`、`E.envNote`、`E.entryChallenge` | 将完整性、环境与上次挑战的回带值写入心跳 |
| `V.verifyNow()` | `E.tampered` | 成功时不清除已确认的篡改状态 |

写入点：`E.load()` 可拒绝缺失凭据；`B.beatOnce()` 经 `E.confirm()`、`E.deny()`、`E.netFail()` 更新授权态；`V.spotCheck()` 和 `V.verifyNow()` 经 `E.tamper()` 或 unavailable 路径更新完整性态；`G.onInitializeClient()` 在启动异常时拒绝。

## 阶段 0 复核

- CONFIRMED：v2.1 的 `W.probe()` 保留在每次 `B.beatOnce()` 开头，`E.noteEnv()` 与心跳 `env` 上报链保留。
- CONFIRMED：`ModOrigin.getPaths()` 用于定位实际 JAR；无法定位归为 unavailable，不归为 mismatch。
- CONFIRMED：原入口在签名授权和完整性通过前不会执行。
- SUSPECTED：环境探针可发现部分已知 agent；自定义类名和 JVM 封装限制可能使其漏报。
