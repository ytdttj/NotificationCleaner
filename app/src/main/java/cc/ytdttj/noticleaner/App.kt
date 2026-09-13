package cc.ytdttj.noticleaner

import android.app.Application
import cc.ytdttj.noticleaner.data.ModelRepository
import cc.ytdttj.noticleaner.data.SettingsRepository
import cc.ytdttj.noticleaner.data.db.AppDatabase
import cc.ytdttj.noticleaner.notify.CleanerListenerService
import cc.ytdttj.noticleaner.notify.KeepAliveManager
import cc.ytdttj.noticleaner.notify.RuleEngine

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

/** 手写 ServiceLocator（Plan.md §2：小项目不引入 Hilt） */
object ServiceLocator {
    private val appJob = kotlinx.coroutines.SupervisorJob()
    /** 应用级协程作用域（与进程同生命周期，供无 VM 场景使用） */
    val appScope = kotlinx.coroutines.CoroutineScope(appJob + kotlinx.coroutines.Dispatchers.Default)

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var modelRepo: ModelRepository
        private set
    lateinit var ruleEngine: RuleEngine
        private set
    lateinit var keepAlive: KeepAliveManager
        private set

    lateinit var appContext: android.content.Context
        private set

    fun modelDir(): String = "model" // delta 等学习文件相对 filesDir 的子目录

    fun init(app: Application) {
        appContext = app.applicationContext
        db = AppDatabase.get(app)
        settings = SettingsRepository(app)
        modelRepo = ModelRepository(app)
        ruleEngine = RuleEngine(db.ruleDao(), db.whitelistDao())
        keepAlive = KeepAliveManager(app)
        CleanerListenerService.initScope(app)
    }
}
