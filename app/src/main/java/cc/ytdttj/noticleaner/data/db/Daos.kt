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

    /** 清理 7 天前未学习的过期通知 */
    @Query("DELETE FROM notifications WHERE learned = 0 AND expireAt < :now")
    suspend fun purgeExpired(now: Long): Int

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
