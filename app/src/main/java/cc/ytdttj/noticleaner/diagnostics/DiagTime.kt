package cc.ytdttj.noticleaner.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 诊断时间戳工具（2.0.1 Dev 12，上岛延迟排查用）。
 *
 * 背景：诊断日志的行首时间戳是**我们处理到该事件**的时刻，而通知真正的
 * 发布时间是 `StatusBarNotification.postTime`。此前日志只有前者，导致
 * "微信 21:43:41 发的支付通知、我们 21:47:41 才收到"这种**系统投递积压**
 * 只能靠交叉比对数据库 CSV 才能发现（4 分钟延迟就是这么看出来的）。
 *
 * 现在每次收到通知都会记下 `post=<原始发布时间>` 与 `lag=<滞后毫秒>`：
 * - lag 几百毫秒内：投递正常
 * - lag 几十秒以上：系统在灭屏/冻结期间积压，亮屏后才补投 —— **App 处理再快也没用**
 *
 * 格式与环形日志行首一致（`MM-dd HH:mm:ss.SSS`），便于直接对齐阅读。
 * java.time 的 DateTimeFormatter 是线程安全的（minSdk 33 可用），
 * 可在 NLS 回调的并发路径上直接使用（SimpleDateFormat 不行）。
 */
object DiagTime {

    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    /** epoch 毫秒 → `MM-dd HH:mm:ss.SSS`（本地时区） */
    fun stamp(epochMs: Long): String = fmt.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
    )

    /** 人类可读的滞后量：毫秒 / 秒 / 分秒 */
    fun lagText(lagMs: Long): String = when {
        lagMs < 0 -> "${lagMs}ms（postTime 晚于本机时钟）"
        lagMs < 1_000 -> "${lagMs}ms"
        lagMs < 60_000 -> "%.1fs".format(lagMs / 1000.0)
        else -> "${lagMs / 60_000}分${(lagMs % 60_000) / 1000}秒"
    }
}
