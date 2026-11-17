package cc.ytdttj.noticleaner.notify

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍保活层（1.2.1，可选）：
 * 系统绑定的第二条生命线——连接期间进程为"可感知"优先级且系统自动重绑，
 * 断连由系统管理，无需自建看门狗。空实现（不读取窗口内容，仅占绑定）。
 *
 * 联动自愈：连接建立/事件到达时顺带检查 NLS 断连 → 请求重绑
 * （无障碍活跃 = 进程活着 = 修复动作可达）。
 */
class NotiGuardService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        android.util.Log.i("NCWatch", "noti-guard accessibility connected → check NLS")
        runCatching {
            if (CleanerListenerService.isListenerEnabled(this) &&
                !CleanerListenerService.isListenerConnected()
            ) {
                CleanerListenerService.requestRebindIfEnabled(this)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        android.util.Log.i("NCWatch", "noti-guard accessibility unbound")
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        /** 系统设置里本服务是否已启用（真实绑定状态以 [connected] 为准） */
        fun isEnabledInSettings(context: android.content.Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val cn = "$PACKAGE_NAME/${NotiGuardService::class.java.name}"
            return flat.split(':').any { it.equals(cn, ignoreCase = true) }
        }

        private const val PACKAGE_NAME = "cc.ytdttj.noticleaner"
    }
}
