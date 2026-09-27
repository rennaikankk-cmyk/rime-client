# Rime 1.1.1 — 爆炸战斗逻辑与 dickguard3.1

- DEFAULT 水晶改进真实地形伤害、候选排序、实际姿态移动预测与 GZBoost 只读速度协同；Rusher 保持原样。
- TP 水晶改进完整伤害计算、底座遮挡、临时站位自伤、生成空间与 TP 返回，并限制无效重复攻击。
- 锚、床修正完全遮挡和爆炸半径边界的基础伤害；锚补上高速移动沿途自伤，TP 床改进安全站位及返回。
- 活塞水晶按真实掩体计算目标伤害，选点筛选保留红石复用优先级，最终引爆前重新评估。
- 防护升级到上游 dickguard3.1（c912156）：签名清单绑定当前凭据与水印账号，修正清单解码并增加随机 guard 类抽查；保留 Rime 双入口、会员、HWID、水印、心跳和逐动作授权。

客户端版本 1.1.1，防护版本 dickguard3.1，适用于 Minecraft 1.21.11；握手协议标识仍为 dickguard3.0。1.21.1 客户端和 Windows 登录器版本沿用原有发行。

战斗代码已通过 85 项回归，最新独立存档实测通过 54 项检查、18 组原版爆炸伤害对照及 5 类动作方法测试；更早的 DEFAULT 水晶与巡航预测实测记录另见 docs/crystal-default-enhancement-20260927.md。测试不是完整真人对战，不作爆速或胜率提升承诺。

已于 2026-09-28 发布到官网和登录器 1.21.11 下载源；最终验收与发布回执记录在 docs/releases/rime-1.1.1/。

## 发布验收

- 构建源码：63e3b4b；GitHub 标签：rime-v1.1.1。
- 发行基础包：`rime-63e3b4b-1.1.1-dickguard3.1-obfuscated.jar`。
- SHA256：`948f0d1af2adde4b29f0d1ad68792cbc32ee7f480663e300fa7723c87e9669ba`。
- 85 个客户端回归任务、35,959 项混淆产物检查、4 套 guard Java 测试、1 项 Python 合并测试、25 项发布通道测试全部通过。
- 同一份生产水印和签名验证副本在 Microsoft Java 21.0.7 与 Adoptium 21.0.12.1 启动通过，每次稳定观察 20 秒；原客户端类字节在合入 guard 和签名后保持一致，验证账号已撤销。
- 官网页面 HTTP 200 且内容哈希匹配；原始 JAR 继续按既有策略返回 404，由登录器分发逐份会员包。
- 官网文件、登录器签名源、桌面 mods 基础包三处一致；桌面旧版已归档至 guizhouware-backups/rime111-20260928。
- 发布备份：`/var/backups/guizhouware-guard31/20260928-release`；发布通道备份：`/var/backups/guizhouware-guard31/20260928-services`。本次只更新 web-api 的 3.1 接受列表，没有覆盖已部署的授权后端或 Dick subject pin。
