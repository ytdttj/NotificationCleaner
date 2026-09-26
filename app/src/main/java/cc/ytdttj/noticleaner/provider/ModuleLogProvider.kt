package cc.ytdttj.noticleaner.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import cc.ytdttj.noticleaner.ServiceLocator
import cc.ytdttj.noticleaner.ai.takeCodepoints
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import kotlinx.coroutines.runBlocking

/**
 * 模块拦截记录回流入口（1.2.1）：
 * LSPosed 模块在 system_server 内拦截的通知经此写入历史库——
 * 入队前拦截意味着 NLS 收不到这些通知，回流保证历史完整 + 可继续学习标注。
 * exported=true（system_server 访问需要），调用方仅接受 SYSTEM_UID 与自身（对齐 ref/Notice）。
 */
class ModuleLogProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) return null
        values ?: return null
        val ctx = context ?: return null

        // Dev 5：LSPosed 模块心跳（decision=LSP_ALIVE）——不入历史库，
        // 写独立偏好文件供 KeepAliveManager.isLspActive() 作"模块真实在跑"的铁证
        if (values.getAsString(COL_DECISION) == LSP_ALIVE_DECISION) {
            runCatching {
                ctx.getSharedPreferences(LSP_HEARTBEAT_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_alive", values.getAsLong(COL_POST_TIME) ?: System.currentTimeMillis())
                    .putString("last_process", values.getAsString(COL_TITLE).orEmpty())
                    .apply()
            }
            return null
        }

        // Dev 8：Hook 端日志回流（decision=HOOK_LOG）——SystemUI/xmsf/system_server
        // 内的关键事件（岛校验/认证/拦截）写环形日志，随诊断导出覆盖 24 小时
        if (values.getAsString(COL_DECISION) == HOOK_LOG_DECISION) {
            runCatching {
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    "[Hook:${values.getAsString(COL_PACKAGE).orEmpty()}] " +
                        values.getAsString(COL_TITLE).orEmpty() +
                        (values.getAsString(COL_CONTENT)?.takeIf { it.isNotBlank() }?.let { " | $it" } ?: ""),
                )
            }
            return null
        }

        val entity = NotificationEntity(
            packageName = values.getAsString(COL_PACKAGE).orEmpty(),
            appName = appName(ctx, values.getAsString(COL_PACKAGE).orEmpty()),
            channelId = values.getAsString(COL_CHANNEL).orEmpty(),
            channelName = values.getAsString(COL_CHANNEL).orEmpty(),
            // 1.3.2（P1-2）：与 NLS 入库路径同语义截断 500 codepoint（跨路径去重需两端一致）
            title = values.getAsString(COL_TITLE).orEmpty()
                .takeCodepoints(cc.ytdttj.noticleaner.ai.FeatureHasher.MAX_TEXT_LEN),
            content = values.getAsString(COL_CONTENT).orEmpty()
                .takeCodepoints(cc.ytdttj.noticleaner.ai.FeatureHasher.MAX_TEXT_LEN),
            postTime = values.getAsLong(COL_POST_TIME) ?: System.currentTimeMillis(),
            adProbability = values.getAsFloat(COL_PROBABILITY) ?: 0f,
            decision = values.getAsString(COL_DECISION).orEmpty(),
            expireAt = (values.getAsLong(COL_POST_TIME) ?: System.currentTimeMillis()) + EXPIRE_MS,
            key = values.getAsString(COL_KEY).orEmpty(),
        )
        if (entity.packageName.isBlank() || entity.decision.isBlank()) return null

        runBlocking {
            val dao = ServiceLocator.db.notificationDao()
            // 1.3.2（P1-1）：与 NLS 路径共用单事务槽位写入（原三趟独立事务 + 无锁 → 单事务）
            val outcome = dao.upsertSlot(entity, entity.postTime - DEDUP_MS)
            if (outcome?.inserted == true) {
                // 模块拦截也计入常驻通知统计（与 NLS 路径一致；仅新插入计数）
                when (entity.decision) {
                    "FILTERED_BY_AI_MODULE" -> ServiceLocator.settings.incrementFiltered(ai = true)
                    "FILTERED_BY_RULE_MODULE" -> ServiceLocator.settings.incrementFiltered(ai = false)
                }
            }
        }
        return uri.buildUpon().appendPath(entity.key).build()
    }

    private fun appName(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrDefault(pkg)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.cc.ytdttj.noticleaner.modulelog"

    companion object {
        const val AUTHORITY = "cc.ytdttj.noticleaner.modulelog"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/log")

        /** Dev 5：模块心跳决策标记（system_server 内 hook 成功后回写，不入库） */
        const val LSP_ALIVE_DECISION = "LSP_ALIVE"
        const val LSP_HEARTBEAT_PREFS = "lsp_heartbeat"

        /** Dev 8：Hook 端日志回流标记（SystemUI/xmsf 进程关键事件 → RingLog，不入库） */
        const val HOOK_LOG_DECISION = "HOOK_LOG"

        const val COL_PACKAGE = "package"
        const val COL_CHANNEL = "channel"
        const val COL_TITLE = "title"
        const val COL_CONTENT = "content"
        const val COL_POST_TIME = "post_time"
        const val COL_PROBABILITY = "probability"
        const val COL_DECISION = "decision"
        const val COL_KEY = "key"

        private const val EXPIRE_MS = 7L * 24 * 60 * 60 * 1000
        private const val DEDUP_MS = 60_000L
    }
}
