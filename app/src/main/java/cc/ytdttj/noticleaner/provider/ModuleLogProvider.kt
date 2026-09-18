package cc.ytdttj.noticleaner.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import cc.ytdttj.noticleaner.ServiceLocator
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

        val entity = NotificationEntity(
            packageName = values.getAsString(COL_PACKAGE).orEmpty(),
            appName = appName(ctx, values.getAsString(COL_PACKAGE).orEmpty()),
            channelId = values.getAsString(COL_CHANNEL).orEmpty(),
            channelName = values.getAsString(COL_CHANNEL).orEmpty(),
            title = values.getAsString(COL_TITLE).orEmpty(),
            content = values.getAsString(COL_CONTENT).orEmpty(),
            postTime = values.getAsLong(COL_POST_TIME) ?: System.currentTimeMillis(),
            adProbability = values.getAsFloat(COL_PROBABILITY) ?: 0f,
            decision = values.getAsString(COL_DECISION).orEmpty(),
            expireAt = (values.getAsLong(COL_POST_TIME) ?: System.currentTimeMillis()) + EXPIRE_MS,
            key = values.getAsString(COL_KEY).orEmpty(),
        )
        if (entity.packageName.isBlank() || entity.decision.isBlank()) return null

        runBlocking {
            val dao = ServiceLocator.db.notificationDao()
            val existing = dao.findByKey(entity.key)
            if (existing != null) {
                dao.update(existing.copy(decision = entity.decision, adProbability = entity.adProbability))
            } else {
                val dup = dao.findRecentDuplicate(
                    entity.packageName, entity.title, entity.content, entity.postTime - DEDUP_MS,
                )
                if (dup == null) {
                    dao.insert(entity)
                    // 模块拦截也计入常驻通知统计（与 NLS 路径一致）
                    when (entity.decision) {
                        "FILTERED_BY_AI_MODULE" -> ServiceLocator.settings.incrementFiltered(ai = true)
                        "FILTERED_BY_RULE_MODULE" -> ServiceLocator.settings.incrementFiltered(ai = false)
                    }
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
