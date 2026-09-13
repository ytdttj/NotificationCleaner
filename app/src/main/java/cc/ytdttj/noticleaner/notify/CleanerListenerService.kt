package cc.ytdttj.noticleaner.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.ai.SpamModel
import cc.ytdttj.noticleaner.data.ModelRepository
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_AI
import cc.ytdttj.noticleaner.data.db.DECISION_FILTERED_BY_RULE
import cc.ytdttj.noticleaner.data.db.DECISION_PASSED
import cc.ytdttj.noticleaner.data.db.DECISION_WHITELIST
import cc.ytdttj.noticleaner.data.db.NotificationDao
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * 通知监听服务（系统入口，Plan.md §5.6）：
 * 收到通知 → 硬放行护栏 → 规则匹配 → AI 打分 → 阈值决策 → 清除/放行，全部入库留档。
 */
class CleanerListenerService : NotificationListenerService() {

    companion object {
        const val SELF_PACKAGE = "cc.ytdttj.noticleaner"

        @Volatile
        private var activeInstance: CleanerListenerService? = null

        /** 按通知 key 清除系统通知栏中的通知（供「学习为广告」联动使用） */
        fun cancelByKey(key: String) {
            val svc = activeInstance ?: return
            runCatching { svc.cancelNotification(key) }
        }

        /** 硬放行词表：误杀代价极高，内容命中直接 PASSED（Plan.md §5.6 护栏） */
        private val HARD_ALLOW_WORDS =
            listOf("验证码", "动态码", "校验码", "OTP", "verification code", "one-time")

        private val EXPIRE_MS = TimeUnit.DAYS.toMillis(7)
        private val DEDUP_WINDOW_MS = TimeUnit.SECONDS.toMillis(60)

        // ---- 决策热路径缓存（1.0.7：消除每条通知的 DataStore/PackageManager 往返，降低拦截延迟） ----
        @Volatile
        var cachedThreshold: Float = 0.8f
            private set

        @Volatile
        var cachedIntercept: Boolean = true
            private set

        private val appNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** 入库互斥：并发 onNotificationPosted 处理时防止查重-插入竞态双插 */
        private val insertMutex = kotlinx.coroutines.sync.Mutex()

        @Volatile
        private var appScope: CoroutineScope? = null

        fun initScope(context: Context) {
            if (appScope == null) {
                appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                // 设置项持续同步到内存，决策路径零 IO
                appScope!!.launch {
                    ServiceLocator.settings.threshold.collect { cachedThreshold = it }
                }
                appScope!!.launch {
                    ServiceLocator.settings.interceptMode.collect { cachedIntercept = it }
                }
            }
            ServiceLocator.ruleEngine.start(appScope!!)
        }

        /** 监听服务是否已授权并连接 */
        fun isListenerEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            if (TextUtils.isEmpty(flat)) return false
            val cn = ComponentName(context, CleanerListenerService::class.java)
            return flat.split(":").any {
                ComponentName.unflattenFromString(it)?.equals(cn) == true
            }
        }

        /** 监听断线时请求系统重绑（Plan.md §7.1 看门狗的自愈路径） */
        fun requestRebind(context: Context) {
            if (!isListenerEnabled(context)) return
            runCatching {
                requestRebindCompat(context)
            }
        }

        private fun requestRebindCompat(context: Context) {
            // NotificationListenerService.requestRebind 为静态方法（API 24+）
            NotificationListenerService.requestRebind(
                ComponentName(context, CleanerListenerService::class.java),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        initScope(this)
        val scope = appScope ?: return
        scope.launch {
            ServiceLocator.db.notificationDao().purgeExpired(System.currentTimeMillis())
        }
        KeepAliveService.start(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        KeepAliveService.start(this)
    }

    override fun onListenerDisconnected() {
        // 监听断线（进程被杀后系统回收绑定）→ 自愈重绑（Plan.md §7.1）
        requestRebindCompat(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        appScope?.cancel()
        appScope = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == SELF_PACKAGE) return
        val notification: Notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val text = listOf(content, bigText).filter { it.isNotEmpty() }
            .distinct().joinToString(" ").ifEmpty { content }
        if (title.isEmpty() && text.isEmpty()) return

        val scope = appScope ?: return
        scope.launch { handle(sbn, title, text) }
    }

    private suspend fun handle(sbn: StatusBarNotification, title: String, content: String) {
        val locator = ServiceLocator
        val dao: NotificationDao = locator.db.notificationDao()
        val modelRepo: ModelRepository = locator.modelRepo

        val pkg = sbn.packageName
        val appName = appNameCache.getOrPut(pkg) { appLabel(pkg) }
        val notification = sbn.notification
        val channel = notification?.channelId.orEmpty()
        // 渠道名展示层解析：详情页展示渠道 ID（Plan.md §6.1）

        val postTime = sbn.postTime
        val joined = listOf(title, content).filter { it.isNotEmpty() }.joinToString("\n")

        // ---- 决策（1.0.7：阈值/拦截模式走内存缓存，热路径零 IO）----
        var decision = DECISION_PASSED
        var probability = 0f

        val protectedType = NotificationProtector.classifyType(notification)
        when {
            // 内置保护类型：媒体/对话/常驻通知完全不参与过滤，仅入库留档
            protectedType != null -> decision = protectedType
            else -> {
                val whitelisted = locator.ruleEngine.isWhitelisted(pkg)
                val rule = locator.ruleEngine.match(pkg, title, content)
                when {
                    rule != null -> decision = DECISION_FILTERED_BY_RULE
                    whitelisted -> decision = DECISION_WHITELIST // 跳过 AI 过滤（规则仍生效，见上）
                    else -> {
                        val normalized = cc.ytdttj.noticleaner.ai.FeatureHasher.normalize(joined)
                        val hardAllow = normalized.length < 4 ||
                            HARD_ALLOW_WORDS.any { joined.contains(it, ignoreCase = true) }
                        if (!hardAllow) {
                            // 模型不可用（assets 缺失/损坏）时跳过打分，按放行处理
                            val p = modelRepo.get()?.score(joined) ?: 0.0
                            probability = p.toFloat()
                            if (p >= cachedThreshold && cachedIntercept) {
                                decision = DECISION_FILTERED_BY_AI
                            }
                        }
                    }
                }
            }
        }

        if (decision == DECISION_FILTERED_BY_AI || decision == DECISION_FILTERED_BY_RULE) {
            runCatching { cancelNotification(sbn.key) }
        }

        // ---- 入库（1.1.6：按槽位 key 去重 + 互斥，防并发双插）----
        insertMutex.withLock {
            // 同一通知槽位（sbn.key）= 通知栏同一条通知：内容更新就地覆盖，不拆新行
            val existing = dao.findByKey(sbn.key)
            if (existing != null) {
                dao.update(
                    existing.copy(
                        title = title,
                        content = content,
                        postTime = postTime,
                        adProbability = probability,
                        decision = decision,
                        expireAt = postTime + EXPIRE_MS,
                    ),
                )
                countFiltered(decision, existing.decision)
                return
            }
            // 60 秒内同 App + 同标题 + 同内容、不同槽位的重复推送：不再重复入库
            val dup = dao.findRecentDuplicate(pkg, title, content, postTime - DEDUP_WINDOW_MS)
            if (dup != null) return

            dao.insert(
                NotificationEntity(
                    packageName = pkg,
                    appName = appName,
                    channelId = channel,
                    channelName = channel,
                    title = title,
                    content = content,
                    postTime = postTime,
                    adProbability = probability,
                    decision = decision,
                    expireAt = postTime + EXPIRE_MS,
                    key = sbn.key,
                ),
            )
            countFiltered(decision, null)
        }
    }

    /** 累计拦截计数（1.1.5，常驻通知展示）：仅在新拦截时 +1，同槽位重复更新不重复计数 */
    private suspend fun countFiltered(decision: String, previous: String?) {
        val isAi = decision == DECISION_FILTERED_BY_AI
        val isRule = decision == DECISION_FILTERED_BY_RULE
        if (!isAi && !isRule) return
        if (previous == decision) return
        runCatching { ServiceLocator.settings.incrementFiltered(ai = isAi) }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)
}
