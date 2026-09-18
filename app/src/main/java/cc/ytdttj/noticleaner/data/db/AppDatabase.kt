package cc.ytdttj.noticleaner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationEntity::class, RuleEntity::class, WhitelistEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun ruleDao(): RuleDao
    abstract fun whitelistDao(): WhitelistDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "notification_cleaner.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `whitelist` (" +
                        "`packageName` TEXT NOT NULL PRIMARY KEY, " +
                        "`appName` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `rules` ADD COLUMN `conditions` TEXT")
            }
        }

    /** 1.1.11：通知表新增 learnCount（重复学习次数），旧数据默认 0 */
    private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `notifications` ADD COLUMN `learnCount` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** 1.2.0（ImprovePlan P0-1）：通知表加索引，消除决策热路径全表扫描 */
    private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_key` ON `notifications` (`key`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_notifications_packageName_title_content_postTime` " +
                    "ON `notifications` (`packageName`, `title`, `content`, `postTime`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_expireAt` ON `notifications` (`expireAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_learned` ON `notifications` (`learned`)")
        }
    }
    }
}
