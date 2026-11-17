package cc.ytdttj.noticleaner.keepalive

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import cc.ytdttj.noticleaner.BuildConfig
import cc.ytdttj.noticleaner.data.ModuleConfigCodec
import cc.ytdttj.noticleaner.provider.ModuleLogProvider

/**
 * 模块端拦截记录回流（1.2.1，借鉴 ref/Notice LogSink）：
 * system_server 内单线程缓冲，应用进程存活时立即写入其 ContentProvider，
 * 否则缓冲至 30s 或 50 条（先到者为准）再刷出——插入会拉起应用进程，批量限频防打扰。
 * APP 启动时广播 [ModuleConfigCodec.ACTION_FLUSH_LOGS] 收集全部待写条目。
 */
internal class ModuleLogSink {

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nc-module-log").apply { isDaemon = true }
    }
    private val pending = ArrayDeque<ContentValues>()
    private var scheduled: ScheduledFuture<*>? = null
    private var receiverRegistered = false

    fun submit(ctx: Context, values: ContentValues) {
        worker.execute {
            ensureReceiver(ctx)
            pending.addLast(values)
            while (pending.size > MAX_PENDING) pending.removeFirst()
            when {
                appRunning(ctx) || pending.size >= FLUSH_THRESHOLD -> flush(ctx)
                scheduled == null -> scheduled = worker.schedule(
                    { flush(ctx) },
                    FLUSH_DELAY_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun flush(ctx: Context) {
        scheduled?.cancel(false)
        scheduled = null
        while (pending.isNotEmpty()) {
            val values = pending.removeFirst()
            try {
                ctx.contentResolver.insert(ModuleLogProvider.CONTENT_URI, values)
            } catch (t: Throwable) {
                android.util.Log.w("NCWatch", "module log insert failed: $t")
            }
        }
    }

    private fun appRunning(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.any { it.pkgList?.contains(BuildConfig.APPLICATION_ID) == true } == true
    } catch (t: Throwable) {
        false
    }

    private fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                worker.execute { flush(ctx) }
            }
        }
        try {
            val filter = IntentFilter(ModuleConfigCodec.ACTION_FLUSH_LOGS)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            android.util.Log.w("NCWatch", "module log flush receiver register failed: $t")
        }
    }

    companion object {
        private const val FLUSH_DELAY_MS = 30_000L
        private const val FLUSH_THRESHOLD = 50
        private const val MAX_PENDING = 2_000
    }
}
