# NotificationCleaner · 通知净化

端上 AI 过滤通知栏广告，支付通知自动上岛。推理、学习、规则匹配**全部在本机完成**，无云端依赖。

[![Release](https://img.shields.io/github/v/release/ytdttj/NotificationCleaner)](https://github.com/ytdttj/NotificationCleaner/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/Android-13%2B-3DDC84)](https://developer.android.com/about/versions/13)
[![HyperOS](https://img.shields.io/badge/%E8%B6%85%E7%BA%A7%E5%B2%9B-HyperOS%203%2B-FF6900)](#局限性)

> **链路**　通知投递 → 端上模型决策 →（可选）LSPosed 在 system_server 内提前拦截 → 支付通知经 SystemUI 代发上岛

---

## 功能特性

**通知净化**
- 端上逻辑回归模型（字符 1~3-gram 哈希特征，2^18 桶），阈值 0.5–1.0 可调，默认 0.8
- 内置保护：验证码硬放行；媒体播放、会话、常驻（进度/来电）通知默认不过滤
- 手动规则：按「应用 + 标题/内容关键字」建规则，白名单应用完全跳过 AI
- 通知历史：未学习保留 7 天、已学习永久保留，详情页可直达对应通知渠道设置

**端上学习（越用越准）**
- 任意通知一键标注「广告 / 正常」，在冻结基线上重拟合稀疏增量，取消标注可精确回滚
- 学习到「应用 + 通知通道」粒度：同渠道后续推送自带广告偏置
- 重复标注累积权重（上限 10）；基线模型升级后学习成果自动继承
- 统计明细页支持批量学习，整批只做一次重拟合

**超级岛**（实验性）
- 支付类通知自动上岛，可按应用逐个开关
- 经 SystemUI 代发 + 盲窗绕过官方白名单与云认证，无需向小米申请授权

**监听自愈**
- 前台服务 + 精确闹钟看门狗自动重连通知监听，失效时悬浮与锁屏提醒并内置「立即修复」
- 控制中心磁贴：Shizuku / Root 可强制重绑，普通用户一键跳转权限页

**诊断与更新**
- 24 小时监控式环形日志（通知接收 / 决策 / 岛链路 / 看门狗 / 崩溃栈），崩溃重启不丢失，一键导出
- 应用内更新：稳定版走 Gitee、Dev 测试版走 GitHub，双通道可选，sha256 校验

**液态玻璃界面**
- Material You 动态取色 + 连续曲率圆角，可切换「柔光 / 磨砂」两档玻璃，悬浮底栏实时透视页面内容

---

## 局限性

| 限制 | 说明 |
|---|---|
| **超级岛仅适配 HyperOS 3 及以上** | 需 `notification_focus_protocol ≥ 3`（OS3 才引入超级岛协议）。HyperOS 1 / 2 仅有旧版焦点通知；**非小米 ROM 完全不可用**。条件不满足时该功能整体禁用，不影响其余功能 |
| **上岛依赖系统实现细节** | 靠「SystemUI 代发 + 盲窗」绕过白名单与云认证，小米若变更协议或认证行为可能失效。已做开关 + 全路径 try-catch，任何岛异常都不影响通知净化主流程 |
| **零延迟过滤依赖 LSPosed** | 不装模块时退化为通知监听器路径，过滤仍可用，但在后台冻结场景下存在投递延迟；上岛功能必须有模块 |
| **仅 arm64-v8a** | 已剔除 armeabi-v7a / x86 / x86_64 原生库，精简体积 |
| **Android 13+** | minSdk 33（液态玻璃界面依赖 Material 3 Expressive） |
| **端上小模型** | 字符 n-gram + 逻辑回归，非大模型；冷启动阶段准确率依赖用户标注学习 |
| **金额解析失败则不上岛** | 支付通知中提取不到金额特征时跳过，原通知照常显示 |

---

## 下载与安装

1. 从 [GitHub Releases](https://github.com/ytdttj/NotificationCleaner/releases/latest) 或 [Gitee Releases](https://gitee.com/ytdttj/NotiCleaner/releases) 下载最新稳定版
2. 授予通知访问权限，按引导关闭省电限制（后台冻结会影响通知实时性）
3. **可选**：在 LSPosed 管理器中启用本模块（作用域 = `system`），获得零延迟过滤与超级岛能力；改完需重启一次手机

Dev 测试版：应用内「设置 → 更新通道 → Dev 版」切换至 GitHub Dev 通道。

## 构建

```bash
git clone https://github.com/ytdttj/NotificationCleaner.git
cd NotificationCleaner
./gradlew assembleRelease
```

- Android Studio Ladybug+ / AGP 8.13 / Kotlin 2.2.20 / JDK 17
- LSPosed 模块基于 libxposed Modern API 102（`META-INF/xposed` 注册）
- 模型训练脚本见 `training/`（Python）：

```bash
pip install -r training/requirements.txt
python training/train.py --extra training/samples/real_history.csv --extra training/samples/bank_tx.csv --extra-weight 30
# 产物 training/model_out/model.bin 需拷入 app/src/main/resources/model/
```

## 隐私

- 模型推理、学习、规则匹配全部在设备本地完成
- 联网权限仅用于「检查更新 / 下载 APK」
- 通知数据仅存本地 Room 数据库，不上传任何服务器
- 环形日志同样只存本机私有目录，仅在用户主动导出时才离开设备

## 许可证

[MIT](LICENSE)　·　更新记录见 [CHANGELOG.md](CHANGELOG.md)
