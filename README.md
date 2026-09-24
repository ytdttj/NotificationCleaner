# NotificationCleaner · 通知净化

利用本地 AI 模型，清理你的手机通知栏。所有推理与学习均在端上完成，无任何云端依赖。

[![Release](https://img.shields.io/github/v/release/ytdttj/NotificationCleaner)](https://github.com/ytdttj/NotificationCleaner/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Min SDK](https://img.shields.io/badge/Android-13%2B-3DDC84)](https://developer.android.com/about/versions/13)

> **v2.0.0**：全新液态玻璃界面、重训的 AI 模型、24 小时监控式日志，以及"监听失效自动提醒 + 一键修复"。详见 [CHANGELOG.md](CHANGELOG.md)。

## 功能

- **AI 广告过滤**：端上逻辑回归模型（字符 1~3-gram 哈希特征，2^18 桶），按可调阈值（0.5–1.0，默认 0.8）自动清除广告通知
- **端上学习（越用越好用）**：
  - 对任意通知点「广告 / 正常」标注，模型在**冻结基线**上全量重拟合稀疏增量（delta），取消标注可精确回滚
  - 学习 APP + 通知通道：同一 APP 同一渠道的后续推送直接获得广告偏置
  - 重复学习同一条通知会累积权重（上限 10），方向明确后越学越准
  - **基线模型升级后自动重拟合**：你的学习成果跨版本继承，不会因换模型丢失
- **手动规则**：按 APP + 标题/内容关键字建规则，白名单 APP 完全跳过 AI
- **内置保护**：验证码等硬放行；媒体播放、会话、常驻（进度/来电）通知默认不过滤
- **通知历史**：未学习保留 7 天，已学习永久保留；详情页可跳转对应通知渠道设置
  - 统计明细页支持**批量学习**：按每条通知原有方向一键重新学习，整批只做一次重拟合
- **监控式环形日志**（2.0.0 新增）：全链路关键事件（通知接收/决策/岛链路/看门狗/崩溃栈）写入文件循环存储，**保留近 24 小时**，进程崩溃与重启不丢失；「设置 → 导出诊断日志」一键导出
- **监听自愈与修复**（2.0.0 新增）：
  - 前台服务 + 闹钟看门狗自动重连通知监听
  - 监听失效时发出**悬浮 + 锁屏可见**的提醒，内置「立即修复」按钮
  - **控制中心磁贴**：Shizuku / Root 直接执行强制重绑；普通用户一键跳转权限页
- **液态玻璃界面**（2.0.0 新增）：Material You 动态取色 + 连续曲率圆角；可切换「柔光 / 磨砂」两档玻璃，悬浮底栏实时透视页面内容
- **超级岛（实验）**：支付类通知上岛（HyperOS 焦点通知协议），白名单可逐 App 开关
- **应用内更新**：稳定版走 Gitee、Dev 测试版走 GitHub，双通道可选，sha256 校验

## 下载

- 稳定版：[GitHub Releases](https://github.com/ytdttj/NotificationCleaner/releases/latest) 或 [Gitee Releases](https://gitee.com/ytdttj/NotiCleaner/releases)
- Dev 测试版：本 APP 内「设置 → 更新通道 → Dev 版」即可切换到 GitHub Dev 通道

## 构建

```bash
git clone https://github.com/ytdttj/NotificationCleaner.git
cd NotificationCleaner
./gradlew assembleRelease
```

- Android Studio Ladybug+ / AGP 8.13 / Kotlin 2.2.20 / JDK 17
- 仅 arm64-v8a + R8 压缩
- 模型训练脚本见 `training/`（Python）：
  ```bash
  pip install -r training/requirements.txt
  python training/train.py --extra training/samples/real_history.csv --extra training/samples/bank_tx.csv --extra-weight 30
  ```
  产物 `training/model_out/model.bin` 需拷入 `app/src/main/resources/model/`

## LSPosed 模块（可选）

安装后可在 LSPosed 管理器中启用本模块（作用域：system）：

- 在 system_server 内、通知入队前完成拦截（比监听器更早，无过滤延迟）
- 阻止系统停止通知监听服务
- 解锁超级岛焦点通知白名单与云端认证

基于 libxposed Modern API 102（`META-INF/xposed` 注册）。

## 隐私

- 模型推理、学习、规则匹配全部在设备本地完成
- 联网权限仅用于「检查更新 / 下载 APK」
- 通知数据仅存本地 Room 数据库，不上传任何服务器
- 环形日志同样只存本机私有目录，仅在用户主动导出时才离开设备

## 许可证

[MIT](LICENSE)
