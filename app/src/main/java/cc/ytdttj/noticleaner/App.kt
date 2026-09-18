package cc.ytdttj.noticleaner

import android.app.Application
import cc.ytdttj.noticleaner.data.ModelRepository
import cc.ytdttj.noticleaner.data.ModuleConfigSync
import cc.ytdttj.noticleaner.data.SettingsRepository
import cc.ytdttj.noticleaner.data.db.AppDatabase
import cc.ytdttj.noticleaner.notify.CleanerListenerService
import cc.ytdttj.noticleaner.notify.KeepAliveManager
import cc.ytdttj.noticleaner.notify.RuleEngine
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.launch

class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // LSPosed 框架服务（模块激活时由框架绑定）：模块激活状态 + delta 远程文件通道
        runCatching { XposedServiceHelper.registerListener(this) }
        // 模块拦截记录回流：system_server 在 APP 未运行时缓冲，启动即请求刷出
        runCatching {
            sendBroadcast(
                android.content.Intent(cc.ytdttj.noticleaner.data.ModuleConfigCodec.ACTION_FLUSH_LOGS)
                    .setPackage(packageName),
            )
        }
    }

    override fun onServiceBind(service: XposedService) {
        ServiceLocator.onXposedServiceBound(service)
    }

    override fun onServiceDied(service: XposedService) {
        ServiceLocator.onXposedServiceDied()
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
    lateinit var moduleSync: ModuleConfigSync
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
        moduleSync = ModuleConfigSync(app, db.ruleDao(), db.whitelistDao())
        moduleSync.start(appScope)
        CleanerListenerService.initScope(app)
    }

    /** LSPosed 框架服务绑定（模块激活）：注入同步器并补推 delta */
    fun onXposedServiceBound(service: XposedService) {
        moduleSync.xposedService = service
        appScope.launch { moduleSync.onServiceBound() }
    }

    fun onXposedServiceDied() {
        moduleSync.xposedService = null
    }
}
