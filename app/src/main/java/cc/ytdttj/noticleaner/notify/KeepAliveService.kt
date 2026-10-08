package cc.ytdttj.noticleaner.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import cc.ytdttj.noticleaner.R
import cc.ytdttj.noticleaner.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * T0 保活前台服务（Plan.md §7.1）：START_STICKY 常驻，低优先级通知显示累计拦截统计。
 * 1.1.5：内容为「已拦截 AI X 条 · 规则 Y 条」，随拦截实时刷新（DataStore 持久计数）。
 * 1.1.13：闹钟看门狗（Doze 免疫）+ 亮屏/解锁立即自愈；保活通知 Intent 按开关携带
 * FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS（否则经通知拉起的新任务不会隐藏后台卡片）。
 * specialUse 类型（API 34+ 声明 PROPERTY_SPECIAL_USE_FGS_SUBTYPE）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIF_ID = 1001

        /**
         * Dev 10（P1-9）：监听强制修复的触发 action。
         * 修复流程（6+ 条 shell + dumpsys）此前跑在 WatchdogReceiver 的 goAsync 协程里，
         * 极易超出广播 10s 预算（叠加旧的无超时 exec 更是必然超时）→ 系统按接收器超时处理，
         * 后台进程可能被整杀。现在由本常驻服务执行，广播只负责"下单"。
         */
        private const val ACTION_REPAIR = "cc.ytdttj.noticleaner.action.REPAIR"

        /**
         * 超级岛"已完成"按钮（2.1.2）：点击岛上的按钮 → PendingIntent.getService 触发本
         * action → 取消对应通知 → 岛随之清除。SignalDock 实证用 getService（广播型
         * PendingIntent 在 HyperOS 的岛 action 点击链路上无反应），照搬。
         */
        const val ACTION_ISLAND_DISMISS = "cc.ytdttj.noticleaner.action.ISLAND_DISMISS"
        const val EXTRA_ISLAND_NOTIF_ID = "cc.ytdttj.noticleaner.island.NOTIF_ID"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }
        }

        /** 触发一次监听强制修复（服务未运行则顺带拉起） */
        fun startRepair(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_REPAIR)
                // 服务已在运行 → 普通 startService 即可（不会触发后台 FGS 启动限制）；
                // 未运行时 startForegroundService 拉起（onCreate 内 5s 内会 startForeground）
                context.startForegroundService(intent)
            }.onFailure {
                runCatching {
                    context.startService(
                        Intent(context, KeepAliveService::class.java).setAction(ACTION_REPAIR),
                    )
                }
            }
        }
    }

    private var scope: CoroutineScope? = null

    // ---- 1.2.1：常驻通知状态缓存（看门狗在连接状态变化时用最近统计值重建通知） ----
    private var lastAi = 0
    private var lastRule = 0
    private var lastExclude = false
    private var lastConnected: Boolean? = null

    /** 亮屏/解锁自愈（1.1.13）：Doze 期间积压的重绑需求在亮屏瞬间补做 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            android.util.Log.i("NCWatch", "screen event ${intent.action}")
            runCatching {
                if (CleanerListenerService.isListenerEnabled(context) &&
                    !CleanerListenerService.isListenerConnected()
                ) {
                    CleanerListenerService.requestRebindIfEnabled(context)
                    android.util.Log.i("NCWatch", "screen event → rebind requested")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("NCWatch", "keepalive FGS onCreate uptime=${android.os.SystemClock.elapsedRealtime()}")
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keepalive_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }

        // 1.1.13：Doze 免疫的闹钟看门狗 + 亮屏/解锁自愈
        WatchdogReceiver.schedule(this)
        // Dev 9：保活进程也初始化岛代发通道（App.onCreate 走 ServiceLocator；
        // 本服务可能在 Application 逻辑跑完前就绪，两条路径都要覆盖）。
        // 岛在后台场景最依赖这条通道——此时没有 UI 交互，失败用户完全无感。
        runCatching {
            cc.ytdttj.noticleaner.notify.island.IslandDispatch.init(this)
        }
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) },
            )
        }

        // 初始进入前台（必须先 startForeground，再由统计流刷新内容）
        runCatching { startForeground(NOTIF_ID, buildNotification(0, 0, false, connected = null)) }

        // 1.2.1：启动延迟自检——进程被拉起（重绑/开机/升级）后若 1.5s 仍未连接，立即请求重绑
        //（覆盖"进程活但绑定卡死"的滞留态；MainActivity 进入检查已覆盖前台入口场景）
        // 累计拦截统计 + 多任务隐藏开关：任一变化 → 重建常驻通知（Intent 携带正确的隐藏 flag）
        // 1.2.0（ImprovePlan P2-7）：conflate + 1s 节流——拦截风暴时最多每秒刷新一次常驻通知
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            s.launch {
                kotlinx.coroutines.delay(1_500)
                runCatching {
                    if (CleanerListenerService.isListenerEnabled(this@KeepAliveService) &&
                        !CleanerListenerService.isListenerConnected()
                    ) {
                        android.util.Log.i("NCWatch", "fgs startup self-check → rebind requested")
                        CleanerListenerService.requestRebindIfEnabled(this@KeepAliveService)
                    }
                }
            }
            s.launch {
                combine(
                    ServiceLocator.settings.filteredAiCount,
                    ServiceLocator.settings.filteredRuleCount,
                    ServiceLocator.settings.excludeFromRecents,
                ) { a, r, e -> Triple(a, r, e) }
                    .conflate()
                    .collect { (a, r, e) ->
                        lastAi = a; lastRule = r; lastExclude = e
                        runCatching {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIF_ID, buildNotification(a, r, e, connected = lastConnected))
                        }
                        kotlinx.coroutines.delay(1_000)
                    }
            }
            // 协程看门狗（1.1.11：间隔 60s→30s）：亮屏期间的快速自愈路径；
            // Doze 下会被挂起，由 WatchdogReceiver 闹钟兜底
            // 1.2.1：连接状态变化时同步刷新常驻通知文案（"监听重连中…"可见化）
            s.launch {
                while (true) {
                    kotlinx.coroutines.delay(30_000)
                    runCatching {
                        val enabled = CleanerListenerService.isListenerEnabled(this@KeepAliveService)
                        val connected = CleanerListenerService.isListenerConnected()
                        if (enabled && !connected) {
                            CleanerListenerService.requestRebindIfEnabled(this@KeepAliveService)
                        }
                        if (connected != lastConnected) {
                            lastConnected = connected
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIF_ID, buildNotification(lastAi, lastRule, lastExclude, connected = connected))
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPAIR -> runRepairAsync()
            ACTION_ISLAND_DISMISS -> handleIslandDismiss(intent)
        }
        return START_STICKY
    }

    /**
     * 超级岛"已完成"（2.2.0 Dev 3 修正）：
     *
     * 岛通知有两条来源，取消必须**两条都走**：
     * - 代发路径（常态）：通知由 SystemUI 进程以 com.android.systemui 身份发出，
     *   App 的 [NotificationManager.cancel] 按包名隔离 → 对该 id 是**空操作**
     *   （Dev 2 真机实测：App 打了"已取消"日志，SystemUI 侧从未出现 notification_canceled，
     *   岛一直不消——用户表现为"点了没反应"）。故广播给 SystemUI 由它自己取消。
     * - 回退路径（代发未就绪）：通知归本 App，自身 cancel 即生效。
     */
    private fun handleIslandDismiss(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ISLAND_NOTIF_ID, -1)
        if (id == -1) return
        val dispatched = cc.ytdttj.noticleaner.notify.island.IslandDispatch.isReady()
        if (dispatched) {
            // 代发：取消动作送回 SystemUI 进程执行
            cc.ytdttj.noticleaner.notify.island.IslandDispatch.requestSystemUiDismiss(this, id)
        } else {
            // 回退：通知归本 App，自己取消
            runCatching { getSystemService(NotificationManager::class.java).cancel(id) }
        }
        cc.ytdttj.noticleaner.diagnostics.RingLog.log(
            cc.ytdttj.noticleaner.diagnostics.LogModules.ISLAND,
            if (dispatched) {
                "岛通知已完成：用户点击按钮，已请求 SystemUI 取消代发通知 (id=$id)"
            } else {
                "岛通知已完成：用户点击按钮，已取消通知 (id=$id, 回退路径)"
            },
        )
    }

    /**
     * 监听强制修复（Dev 10，P1-9）：在本服务的作用域内执行，不受广播 goAsync 10s 预算约束。
     * 结果（含系统侧诊断）写入环形日志 KEEP 模块，随诊断导出覆盖 24 小时。
     */
    private fun runRepairAsync() {
        val s = scope ?: return
        s.launch(Dispatchers.IO) {
            val shizukuUsable = runCatching {
                rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (!shizukuUsable) {
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.KEEP,
                    "✗ 看门狗：Shizuku 不可用，跳过强制修复",
                )
                return@launch
            }
            cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                cc.ytdttj.noticleaner.diagnostics.LogModules.KEEP,
                "看门狗：Shizuku 强制修复监听（KeepAliveService 内执行，不受广播预算限制）",
            )
            runCatching {
                val log = ListenerRepair.repair(ShizukuExecutor)
                android.util.Log.i("NCWatch", "listener repair done:\n$log")
                // 2.0.1 Dev 3：系统侧诊断段进环形日志（导出可见，logcat 易滚动）
                val diag = log.substringAfter("---- 系统侧诊断", "")
                if (diag.isNotBlank()) {
                    val trimmed = diag.lineSequence().take(45).joinToString("\n")
                    cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                        cc.ytdttj.noticleaner.diagnostics.LogModules.KEEP, "系统侧诊断\n$trimmed",
                    )
                }
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.KEEP,
                    "看门狗：Shizuku 修复完成 → ${log.lineSequence().firstOrNull()?.take(80)}",
                )
            }.onFailure { t ->
                android.util.Log.w("NCWatch", "listener repair failed: $t")
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.KEEP,
                    "✗ 看门狗：Shizuku 修复失败 $t",
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    /**
     * 1.2.1：[connected] 为 null 表示尚未探测（启动瞬间）；false 时文案提示"监听重连中"，
     * 用户看到异常打开 APP 即触发进入自检修复。
     */
    private fun buildNotification(
        aiCount: Int,
        ruleCount: Int,
        excludeFromRecents: Boolean,
        connected: Boolean?,
    ): Notification {
        val launchIntent =
            android.content.Intent(this, cc.ytdttj.noticleaner.ui.MainActivity::class.java)
                // CLEAR_TOP 复用已存在的任务栈，避免返回时叠一层主界面
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
        // 1.1.13：开关开启时 Intent 携带隐藏 flag——任务被系统重建后仍保持隐藏
        if (excludeFromRecents) {
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val stats = "已拦截 AI $aiCount 条 · 规则 $ruleCount 条"
        val text = if (connected == false) "监听重连中… $stats" else stats
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_delete)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_delete)
                .setContentIntent(contentIntent)
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build()
        }
    }
}
