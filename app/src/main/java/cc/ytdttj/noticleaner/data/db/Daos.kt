package cc.ytdttj.noticleaner.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(n: NotificationEntity): Long

    @Update
    suspend fun update(n: NotificationEntity)

    @Query("SELECT * FROM notifications ORDER BY postTime DESC LIMIT 500")
    fun listAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE decision = :decision ORDER BY postTime DESC LIMIT 500")
    fun listByDecision(decision: String): Flow<List<NotificationEntity>>

    /** 按决策集合查询（1.2.1：模块端拦截的 *_MODULE 决策与 NLS 决策合并展示） */
    @Query("SELECT * FROM notifications WHERE decision IN (:decisions) ORDER BY postTime DESC LIMIT 500")
    fun listByDecisions(decisions: List<String>): Flow<List<NotificationEntity>>

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

    /** 60 秒内同 App + 同标题 + 同内容 → 重复推送，不重复入库 */
    @Query(
        "SELECT * FROM notifications WHERE packageName = :pkg AND title = :title AND content = :content " +
            "AND postTime >= :since ORDER BY id DESC LIMIT 1",
    )
    suspend fun findRecentDuplicate(pkg: String, title: String, content: String, since: Long): NotificationEntity?

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
