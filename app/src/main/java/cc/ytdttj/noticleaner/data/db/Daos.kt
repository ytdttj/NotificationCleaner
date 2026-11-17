package cc.ytdttj.noticleaner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(n: NotificationEntity): Long

    @Update
    suspend fun update(n: NotificationEntity)

    /**
     * 槽位写入（1.3.2 P1-1）：findByKey → update 或（findRecentDuplicate 去重后）insert
     * 合并为单事务，fsync 3→1。两处写入路径（NLS handle / ModuleLogProvider）共用。
     * @return 槽位已存在并已 update → [SlotOutcome]（携带原 decision，供拦截计数）；
     *         新插入 → [SlotOutcome]（previousDecision=null, inserted=true）；
     *         60s 去重命中跳过 → null
     */
    @Transaction
    suspend fun upsertSlot(n: NotificationEntity, dedupSince: Long): SlotOutcome? {
        // P2-2：去重哈希在写入时统一计算（update 路径同步刷新，保证存量行哈希与内容一致）
        val hash = contentHashOf(n.title, n.content)
        val existing = findByKey(n.key)
        if (existing != null) {
            val contentChanged = existing.contentHash != 0L && existing.contentHash != hash
            // 20261028 用户实锤（vc90 回归修复，替代 P1-19 的重置语义）：
            // 百度网盘 mipush 广告**复用同一 sbn.key**，每条新广告覆盖同一槽位；
            // P1-19 在内容变化时重置 learned —— 用户逐条学习"很多条"，每条都被后续
            // 广告顶掉，已学习页只剩最新一条，App 桶（需同侧 ≥3 条标注）永远凑不齐，
            // "学习了还是拦不住"。现在改为：**已学习槽位被不同内容顶替时，把旧行冻结
            // 为独立历史行**（key 加后缀让出原键，标题/内容/标注原样保留——refit 的
            // 标签仍对应自己的文本），新内容在原键上全新插入。未学习槽位维持原位覆盖。
            if (contentChanged && existing.learned) {
                update(existing.copy(key = existing.key + "#L" + existing.postTime))
                insert(n.copy(contentHash = hash))
                cc.ytdttj.noticleaner.diagnostics.RingLog.log(
                    cc.ytdttj.noticleaner.diagnostics.LogModules.MODEL,
                    "学习槽位被新内容顶替，已冻结原学习行：${n.packageName} ${n.title.take(20)}",
                )
                return SlotOutcome(previousDecision = null, inserted = true)
            }
            // P1-19（未学习槽位）：内容变化后旧 decision/AI 率已不适用，随新内容刷新；
            // contentHash==0 为哈希列引入前的存量行，只刷新哈希
            update(
                existing.copy(
                    title = n.title,
                    content = n.content,
                    postTime = n.postTime,
                    adProbability = n.adProbability,
                    decision = n.decision,
                    expireAt = n.expireAt,
                    contentHash = hash,
                ),
            )
            return SlotOutcome(previousDecision = existing.decision, inserted = false)
        }
        val dup = findRecentDuplicate(n.packageName, hash, dedupSince)
        if (dup != null) return null
        insert(n.copy(contentHash = hash))
        return SlotOutcome(previousDecision = null, inserted = true)
    }

    /** 应用名回填（1.3.2 P0-5）：仅更新"appName 尚为包名"的行，避免覆盖已解析的历史行 */
    @Query("UPDATE notifications SET appName = :appName WHERE packageName = :pkg AND appName = :pkg")
    suspend fun updateAppName(pkg: String, appName: String)

    @Query("SELECT * FROM notifications ORDER BY postTime DESC LIMIT 500")
    fun listAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE decision = :decision ORDER BY postTime DESC LIMIT 500")
    fun listByDecision(decision: String): Flow<List<NotificationEntity>>

    /** 按决策集合查询（1.2.1：模块端拦截的 *_MODULE 决策与 NLS 决策合并展示） */
    @Query("SELECT * FROM notifications WHERE decision IN (:decisions) ORDER BY postTime DESC LIMIT 500")
    fun listByDecisions(decisions: List<String>): Flow<List<NotificationEntity>>

    /** 决策集合之外的通知（1.3.2 P2-5："正常" tab 下推 SQL，不再内存过滤 500 行） */
    @Query("SELECT * FROM notifications WHERE decision NOT IN (:decisions) ORDER BY postTime DESC LIMIT 500")
    fun listNotInDecisions(decisions: List<String>): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE learned = 1 ORDER BY postTime DESC LIMIT 500")
    fun listLearned(): Flow<List<NotificationEntity>>

    /** 学习标注集（一次性读取，供全量重拟合） */
    @Query("SELECT * FROM notifications WHERE learned = 1")
    suspend fun listLearnedOnce(): List<NotificationEntity>

    /** 清空全部学习标注（重置模型用） */
    @Query("UPDATE notifications SET learned = 0, learnLabel = -1 WHERE learned = 1")
    suspend fun clearLearned()

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: Long): NotificationEntity?

    /** 同一通知槽位（sbn.key）→ 视为同一条通知，内容更新就地覆盖（1.1.6：不再按内容拆行） */
    @Query("SELECT * FROM notifications WHERE `key` = :key ORDER BY id DESC LIMIT 1")
    suspend fun findByKey(key: String): NotificationEntity?

    /** 60 秒内同 App + 同 contentHash（P2-2：64 位哈希替代整段文本等值）→ 重复推送，不重复入库 */
    @Query(
        "SELECT * FROM notifications WHERE packageName = :pkg AND contentHash = :contentHash " +
            "AND postTime >= :since ORDER BY id DESC LIMIT 1",
    )
    suspend fun findRecentDuplicate(pkg: String, contentHash: Long, since: Long): NotificationEntity?

    @Query(
        "SELECT COUNT(*) FROM notifications WHERE decision IN " +
            "('FILTERED_BY_AI','FILTERED_BY_AI_MODULE','FILTERED_BY_RULE','FILTERED_BY_RULE_MODULE')"
    )
    fun filteredCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notifications WHERE learned = 1")
    fun learnedCount(): Flow<Int>

    /**
     * 清理保留期外的过期通知（Dev 6：保留天数可调）。
     * 按 postTime 判定（而非写入时固定的 expireAt），改保留天数立即对存量生效；
     * 已学习的标注（learned=1）永不清理，供模型重拟合。
     */
    @Query("DELETE FROM notifications WHERE learned = 0 AND postTime < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    /** CSV 导出（Dev 6）：全量历史通知，按时间倒序 */
    @Query("SELECT * FROM notifications ORDER BY postTime DESC")
    suspend fun exportAll(): List<NotificationEntity>

    /** 诊断导出（Dev 8）：最近 24 小时的通知历史 */
    @Query("SELECT * FROM notifications WHERE postTime >= :since ORDER BY postTime DESC")
    suspend fun listSince(since: Long): List<NotificationEntity>

    @Query("SELECT DISTINCT packageName, appName FROM notifications")
    suspend fun distinctApps(): List<AppRef>
}

data class AppRef(val packageName: String, val appName: String)

/** 槽位写入结果（1.3.2 P1-1，[NotificationDao.upsertSlot] 返回值，供拦截计数使用） */
data class SlotOutcome(val previousDecision: String?, val inserted: Boolean)

@Dao
interface RuleDao {
    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun listAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun listEnabled(): List<RuleEntity>
}

@Dao
interface WhitelistDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(item: WhitelistEntity)

    @Query("DELETE FROM whitelist WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT * FROM whitelist ORDER BY createdAt DESC")
    fun listAll(): Flow<List<WhitelistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM whitelist WHERE packageName = :packageName)")
    suspend fun contains(packageName: String): Boolean
}
