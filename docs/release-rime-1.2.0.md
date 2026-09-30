# Rime 1.2.0 · rimeguard3.3 native 激活 + shell v2 灰度

2026-09-30。native 防护层从休眠转入生产，自研壳首次接入发行管线。

## 防护口径

- **native 激活**（rimeguard3.3）：构建打 `--native` 标记（生产 dll FNV `f1c13f68b7bb4551` + 生产 native secret + 生产 pool lease）。池密钥流三层托管：构建期 secret + dll 种子（P1b）+ 服务端租约（P1c）。
- **壳 v2 灰度**：rimeguard-shell `cf,condy,strip` 全开（seed 20260930），671 类变换、0 回退；d1/、bridge、mixin 类豁免（loader 敏感面保守处理）。mixin 下一版再评估入壳。
- **分发双通道**：launcher ≥ 1.2.0 声明版本号 → 服务端下发 native jar + rimeguard.dll（sha256 签名响应强校验，落盘游戏根目录）；旧 launcher 无声明 → 继续 3.2 legacy 产物，同 filename 原位替换。存量用户零影响。
- 官网直连下载仍为 3.2（plain 产物无凭据本就不启动；1.2.0 只走登录器通道）。

## 校验记录

- 构建源码：`8267bca`（gradle.properties `mod_version=1.2.0`）。
- 发行候选：`rime-8267bca-1.2.0-rimeguard3.3-native-shelled.jar`，SHA256 `ef99682fb8f9405dd0c759f3fba7dc8281598d8345098e497d3e1b7d0e77bfae`（与生产 jars/ 登记一致）。dll SHA256 `17c55074475d6fe5dcb45956150b838bf0b231b799c395ecd0e6bf1a76a4b650`。
- 合并测试 10/10（含行为级 custody E2E：dll+租约=明文，缺任一=乱码）；guard 四套件全过；客户端构建测试链全绿。
- VerifyMerge 验收（生产 dll + 生产租约）：池 2,695 串、泄漏 0、**2,695/2,695 全量解密回环**、入口/元数据 OK；mixins=69、renamed=660。
- 生产端到端探针（一次性账号全链路）：legacy 三态（无字段/1.1.1/畸形）均无 dll 字段；launcher=1.2.0 → native 水印副本（stamp/bridge/凭据齐）+ dll 票据 sha 校验通过。
- **真机启动验证**（本机 PCL 1.21.11-Fabric 0.19.3 沙盒实例，Mojang java-runtime-delta）：guizhouware 1.2.0 加载进 mod 列表，资源重载/音效引擎/材质图集即主菜单；`guizhouware/edition.txt` 签名文本含生产 `lease=541f25a1…`（租约在真实会话闭环）；服务端日志 `心跳绑定native-hwid`（ngauth 生产证词验证并绑定）。无 edition-boot-error。

## 已知事项

- shell v2 新坑修复：condy bootstrap 分块赋值不能用 blank final（>120 站点触发定值分析拒绝）——已修并回归（rimeguard `faf9a7e`）。
- rimeguard-native 的 GitHub Actions CI 仍故障（早于本次），dll 一律本地构建；恢复 CI 时须确认 Actions secret = 生产 native secret。
- 探针账号/卡密已清理；服务器备份：`/opt/gzwx-auth/server.js.pre-dual-20260930`、`config.json.pre-dual-20260930`、`/root/backup-p1c-20260930/`。
- launcher 1.2.0 exe：SHA256 `617cbb3c1429aebc7f86f9451b234467d9c22e86e41ea239a0b4961752f9fee1`（本地 target 构建；上线分发走官网 files 时需重新登记）。
