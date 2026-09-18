package cc.ytdttj.noticleaner.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cc.ytdttj.noticleaner.BuildConfig
import cc.ytdttj.noticleaner.R
import cc.ytdttj.noticleaner.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * 后台定期更新检查（1.2.2）：WorkManager 周期任务（6 小时，有网络约束），
 * APP 退到后台/进程存活期间也能发现新版本并发通知提醒。
 * 同一版本只提醒一次（notified_vc 记录），避免重复打扰。
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val release = UpdateChecker.checkLatest()?.release ?: return Result.retry()
        if (release.versionCode <= BuildConfig.VERSION_CODE) return Result.success()

        val ctx = applicationContext
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return Result.success()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_NOTIFIED_VC, 0) >= release.versionCode) return Result.success()

        postNotification(ctx, release)
        prefs.edit().putInt(KEY_NOTIFIED_VC, release.versionCode).commit()
        return Result.success()
    }

    private fun postNotification(ctx: Context, release: LatestRelease) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // 无通知权限（进入 APP 时会引导授权）
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.update_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = ctx.getString(R.string.update_channel_desc) },
            )
        }
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(ctx.getString(R.string.update_notif_title, release.versionName))
                .setContentText(ctx.getString(R.string.update_notif_text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx)
                .setContentTitle(ctx.getString(R.string.update_notif_title, release.versionName))
                .setContentText(ctx.getString(R.string.update_notif_text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notification) }
    }

    companion object {
        private const val PREFS = "update_notify"
        private const val KEY_NOTIFIED_VC = "notified_vc"
        private const val CHANNEL_ID = "update_check"
        private const val NOTIF_ID = 2002
        private const val WORK_NAME = "update-check-periodic"

        /** APP 启动时调度（KEEP：已存在不覆盖，周期/约束以首次为准） */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
