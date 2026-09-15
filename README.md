# NotificationCleaner

利用本地 AI 模型，清理你的手机通知栏。所有推理与学习均在端上完成，无任何云端依赖。

[![Release](https://img.shields.io/github/v/release/ytdttj/NotificationCleaner)](https://github.com/ytdttj/NotificationCleaner/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-3DDC84)](https://developer.android.com/about/versions/oreo)

## 功能

- **AI 广告过滤**：本地逻辑回归模型（字符 n-gram 哈希特征），按可调阈值（0.5–1.0，默认 0.8）自动清除广告通知
- **端上学习（越用越好用）**：
  - 对任意通知点「广告 / 正常」标注，模型在冻结基线上全量重拟合稀疏增量（delta），可精确回滚
  - 学习 APP + 通知通道：同一 APP 同一渠道的后续推送直接获得广告偏置
  - 支持对同一通知重复学习，权重随次数增强（上限 10）
- **手动规则**：按 APP + 标题/内容关键字建规则，白名单 APP 完全跳过 AI
- **内置保护**：验证码等硬放行；媒体播放、会话、常驻（进度/来电）通知默认不过滤
- **通知历史**：未学习保留 7 天，已学习永久保留；详情页可跳转对应通知渠道设置
- **后台保活**：前台服务 + 看门狗自动重连通知监听；进阶支持 Shizuku / Root / LSPosed（libxposed API 102）
- **应用内更新**：Gitee 优先、GitHub 兜底，双源检查与下载，sha256 校验

## 下载

前往 [Releases](https://github.com/ytdttj/NotificationCleaner/releases/latest) 下载最新 APK；或在本 APP 内「设置 → 检查更新」自动升级。

## 构建

```bash
git clone https://github.com/ytdttj/NotificationCleaner.git
cd NotificationCleaner
./gradlew assembleRelease
```

- Android Studio Ladybug+ / AGP 8.13 / Kotlin 2.2.20 / JDK 17
- 模型训练脚本见 `training/`（Python，含 5 折交叉验证），训练产物已内置 `app/src/main/assets/model/`

## LSPosed 模块（可选）

安装后可在 LSPosed 管理器中启用本模块（作用域：system），阻止系统停止通知监听服务。
基于 libxposed Modern API 102（`META-INF/xposed` 注册）。

## 隐私

- 模型推理、学习、规则匹配全部在设备本地完成
- 联网权限仅用于「检查更新 / 下载 APK」
- 通知数据仅存本地 Room 数据库，不上传任何服务器

## 许可证

[MIT](LICENSE)
