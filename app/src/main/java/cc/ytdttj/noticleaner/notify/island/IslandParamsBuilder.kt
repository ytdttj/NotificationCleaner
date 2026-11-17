package cc.ytdttj.noticleaner.notify.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import cc.ytdttj.noticleaner.notify.KeepAliveService
import org.json.JSONObject

/**
 * 支付岛通知构建（islandplan.md §1.4）。
 *
 * 摘要态胶囊：左侧原 App 图标 + 右侧金额（"-¥25.00"）；
 * 展开态：baseInfo(App名·方向 + 通知标题) / hintInfo(金额高亮 + 折算或正文)。
 * isShowNotification=false：原通知本来就在通知栏，岛不再重复生成条目。
 * 模板字段遵循 HyperOS 焦点通知 param_v2 协议（island.md §2.2 + SignalDock 实战经验）。
 */
object IslandParamsBuilder {

    private const val CHANNEL_ID = "island_payment"
    private const val PIC_APP = "miui.focus.pic_app"

    /**
     * 岛图标降采样边长（px，Dev 9）。
     *
     * 96×96 ARGB_8888 = 36KB，经 [cc.ytdttj.noticleaner.notify.island.IslandDispatch]
     * 的跨进程代发广播传输时 parcel 总量可稳在 Binder 安全范围内；HyperOS 岛上的
     * 胶囊/展开态图标实际显示尺寸远小于此，视觉无损。
     */
    private const val ICON_PX = 96

    /** 支出红 / 收入绿（展开态金额高亮） */
    private const val COLOR_OUT = "#D94B30"
    private const val COLOR_IN = "#2E9E5B"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "超级岛支付提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "银行/支付类通知的超级岛展示（实验功能）"
                },
            )
        }
    }

    fun build(
        context: Context,
        appName: String,
        payment: PaymentExtractor.Payment,
        title: String,
        content: String,
        sourceIcon: Bitmap?,
        contentIntent: PendingIntent?,
        islandTimeoutSec: Int = Int.MAX_VALUE, // Dev 16：结果岛常驻（property=2），直到"已完成"按钮
        showNotification: Boolean = false,
        autoExpandSec: Int = 5,
        notificationId: Int = 0, // "已完成"按钮清除目标（0 = 不挂清除按钮）
    ): Notification {
        ensureChannel(context)

        // 摘要态胶囊：左图标 + 右金额
        val bigIslandArea = JSONObject().apply {
            put(
                "imageTextInfoLeft",
                JSONObject().put("type", 1).put(
                    "picInfo",
                    JSONObject().put("type", 1).put("pic", PIC_APP),
                ),
            )
            put(
                "imageTextInfoRight",
                JSONObject().put("type", 2).put(
                    "textInfo",
                    JSONObject()
                        .put("title", payment.capsuleText)
                        .put("colorTitle", "#FFFFFF") // 胶囊背景深色，文字用白
                        .put("turnAnim", false)
                        .put("narrowFont", true)
                        .put("showHighlightColor", false),
                ),
            )
        }
        val smallIslandArea = JSONObject().put(
            "picInfo",
            JSONObject().put("type", 1).put("pic", PIC_APP),
        )

        val directionText = when (payment.direction) {
            PaymentExtractor.Direction.IN -> "收入"
            PaymentExtractor.Direction.OUT -> "支出"
            PaymentExtractor.Direction.UNKNOWN -> "收支"
        }
        // 展开态副标题：方向 + 通知原文（原通知怎么发就怎么显示）
        val subtitle = buildString {
            append(directionText)
            val original = title.ifBlank { content }.take(60)
            if (original.isNotBlank()) append(" · ").append(original.replace("\n", " "))
        }

        val baseInfo = JSONObject().apply {
            put("type", 2)
            put("title", appName) // 标题 = 来源 App 名（支付宝/微信/招商银行…）
            put("content", subtitle)
            put("colorTitle", "#000000")
            put("colorTitleDark", "#FFFFFF")
            put("colorContent", "#666666")
            put("colorContentDark", "#B8B8B8")
            put("showDivider", false)
            put("showContentDivider", false)
        }
        val picInfo = JSONObject().put("type", 1).put("pic", PIC_APP)

        // 金额行："已完成"按钮（点击 → 保活服务取消通知 → 岛清除）。
        // 2.1.2：对齐 SignalDock 实证实现——此前用自定义 key（miui.focus.action_dismiss）+
        // PendingIntent.getBroadcast，HyperOS 点击后无反应；SignalDock 用「已知槽位
        // miui.focus.action_main + PendingIntent.getService」且实测可点，照搬这两个要素。
        val actionKey = "miui.focus.action_main"
        val dismissPi = PendingIntent.getService(
            context,
            notificationId,
            Intent(context, KeepAliveService::class.java).apply {
                action = KeepAliveService.ACTION_ISLAND_DISMISS
                putExtra(KeepAliveService.EXTRA_ISLAND_NOTIF_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hintInfo = JSONObject().apply {
            put("type", 1)
            put("title", (if (payment.direction == PaymentExtractor.Direction.IN) "+" else if (payment.direction == PaymentExtractor.Direction.OUT) "-" else "") + payment.currency.display + payment.amountText)
            put("colorTitle", if (payment.direction == PaymentExtractor.Direction.IN) COLOR_IN else COLOR_OUT)
            put("colorTitleDark", if (payment.direction == PaymentExtractor.Direction.IN) "#7BD9A2" else "#FF9B82")
            // 按钮组件：金额行最右"已完成"
            put("actionInfo", JSONObject().apply {
                put("action", actionKey)
                put("actionTitle", "已完成")
                put("actionTitleColor", "#FFFFFF")
                put("actionTitleColorDark", "#000000")
                put("actionBgColor", "#1A1A1A")
                put("actionBgColorDark", "#E0E0E0")
                put("clickWithCollapse", true)
            })
        }

        val paramIsland = JSONObject().apply {
            put("islandProperty", 2) // Dev 16：结果岛（SignalDock 实证：常驻胶囊直到"已完成"/划掉）
            put("islandPriority", 2)
            put("islandTimeout", islandTimeoutSec) // Dev 16：Int.MAX_VALUE = 常驻，不受 120s 自动下岛
            put("dismissIsland", false)
            put("expandedTime", autoExpandSec) // 展开态自动保持 N 秒后收起为胶囊（0=立即收起）
            put("maxSize", false)
            put("needCloseAnimation", true)
            put("bigIslandArea", bigIslandArea)
            put("smallIslandArea", smallIslandArea)
        }

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "payment")
            put("enableFloat", false) // 更新时不自动展开
            put("islandFirstFloat", true) // 首次出现直接弹出展开态
            put("updatable", true) // Dev 16：结果岛常驻，允许后续同会话更新内容
            put("isShowNotification", showNotification) // 测试路径 true：岛被认证拒绝时通知栏至少留痕
            put("ticker", "$appName ${payment.capsuleText.trim()}")
            put("tickerPic", PIC_APP)
            put("baseInfo", baseInfo)
            put("picInfo", picInfo)
            put("hintInfo", hintInfo)
            put("param_island", paramIsland)
        }

        val pics = Bundle().apply {
            // 来源 App 图标；无（如测试路径）时回退到本 App launcher 图标，保证展开态右侧始终有图
            val drawable = sourceIcon?.let {
                android.graphics.drawable.BitmapDrawable(context.resources, it)
            } ?: runCatching {
                context.packageManager.getApplicationIcon(context.packageName)
            }.getOrNull()
            // Dev 9：图标**必须**降采样后再进 Bundle。原实现直接塞 getApplicationIcon
            // 的原始位图（部分银行 App 图标 192×192 ARGB_8888 = 147KB），而代发广播要
            // 把整个 extras 跨进程传 给 SystemUI——2026-10-04 实测单条 parcel 达
            // 329624 字节，BroadcastQueue 直接抛 TransactionTooLargeException 把广播丢掉，
            // 岛不上（且 App 侧无回执，仍误记「已提交」）。144×144 = 82KB，仍偏大；
            // 这里压到 96×96（36KB）并只保留一份，parcel 稳定在几十 KB 量级。
            val icon = drawableToBitmap(drawable, ICON_PX)
                ?.let { android.graphics.drawable.Icon.createWithBitmap(it) }
            icon?.let { putParcelable(PIC_APP, it) }
        }

        val extras = Bundle().apply {
            putString("miui.focus.param", JSONObject().put("param_v2", paramV2).toString())
            if (pics.size() > 0) putBundle("miui.focus.pics", pics)
            // "已完成"按钮：HyperOS 通过 actionInfo.action key 引用原生 Action，点击触发 PendingIntent
            if (notificationId != 0) {
                val actions = Bundle().apply {
                    putParcelable(
                        actionKey,
                        Notification.Action.Builder(null, "已完成", dismissPi).build(),
                    )
                }
                putBundle("miui.focus.actions", actions)
            }
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$appName · $directionText")
            .setContentText(payment.capsuleText.trim())
            .setOngoing(false)
            .setAutoCancel(true)
            // Dev 5（借鉴 ref/HyperIsland）：常规路径通知栏完全无痕（岛照常展示）；
            // 测试路径 PRIVATE 留痕，判别认证拒绝
            .setVisibility(
                if (showNotification) Notification.VISIBILITY_PRIVATE
                else Notification.VISIBILITY_SECRET,
            )
            .addExtras(extras)
        contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /**
     * 原 App 图标转 Bitmap（跨包资源图标在 SystemUI 侧解析不可靠，统一落位图）。
     *
     * [targetPx] > 0 时等比降采样到该边长（Dev 9：控制代发广播的 parcel 体积，
     * 见 [ICON_PX] 注释）；<= 0 表示沿用原始尺寸。
     */
    fun drawableToBitmap(drawable: Drawable?, targetPx: Int = 0): Bitmap? {
        val d = drawable ?: return null
        if (d is BitmapDrawable && d.bitmap != null && targetPx <= 0) return d.bitmap
        val w0 = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h0 = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        val (w, h) = if (targetPx > 0 && (w0 > targetPx || h0 > targetPx)) {
            // 等比缩小（短边为目标边长），不放大——避免小图被插值糊掉
            val scale = targetPx.toFloat() / minOf(w0, h0)
            (w0 * scale).toInt().coerceAtLeast(1) to (h0 * scale).toInt().coerceAtLeast(1)
        } else {
            w0 to h0
        }
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
            bmp
        }.getOrNull()
    }
}
