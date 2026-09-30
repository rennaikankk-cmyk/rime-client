# Rime 1.1.9 · rimeguard3.3 发行

2026-09-30。本版把自研 native 防护层并入主干（休眠态），并搭载共享爆炸结算引擎与中文默认界面。

## 防护口径（重要）

- **native 层本版休眠**：构建未打 `--native` 标记。launcher 尚未分发 rimeguard.dll，required-mode 产物会在启动门禁处拒绝全体用户。激活前置条件：launcher dll 分发通道、服务端 rimeguard 节（v2 证人）、dll 签名。目标 1.2.0。
- 已并入且逐项验证过的 native 能力（见 rimeguard-native 仓与 claude/rimeguard-native-p1 分支）：
  - P1a 启动门禁 + dll 环境证词（探针只上报不执法，服务端反制形态）
  - P1b 池密钥托管：池密钥流混入 dll 独有 HMAC 种子；E2E 实测（真实混淆 guard + 真实 dll）有 dll 出明文、无 dll 出乱码——Polaris 式租借收割对本产品线终结
  - v2 witness：authMaterial 织入心跳（native 协议 v2；服务端需配 rimeguard 节激活）
- 自研混淆壳 rimeguard-shell v2（拼接模板 condy + 运行时不可折叠谓词，CFR 实测拒绝反编译）尚未接入客户端发布管线，下一版灰度。

## 校验记录

- 构建源码：107b74d；标签：rime-v1.1.9-rimeguard3.3。
- 发行包：`rime-107b74d-1.1.9-rimeguard3.3-obfuscated.jar`。
- SHA256：`97299f701659f95d1331882e342fea260064cb7a0b616f940d3e730a0da568fb`。
- 合并测试 8/8（含 P1b custody 断言）；guard 四套件（GuardRegression / IntegritySmoke / EntrySession / MockHeartbeat）全过；客户端构建 99 组测试 + 37,592 项混淆检查通过。
- 发行包自检：custom.rimeguard=3.3、入口 main+client 均指向 d1.G、池 2,695 串/332 类、660 类不可落盘改名、META-INF/rimeguard-native.json 按计划缺失、bridge 类不在包内。

## 待办（管理通道，不阻塞本版）

- 官网首页/更新日志/launcher 下载源同步
- launcher dll 分发通道 + gzwx-auth rimeguard 节部署 → 1.2.0 native 激活
- GitHub Actions 计费修复（rimeguard-native CI）
