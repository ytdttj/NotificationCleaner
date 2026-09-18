package cc.ytdttj.noticleaner.notify

/**
 * NLS 决策路径与 LSPosed 模块端（system_server 入队前拦截）共用的护栏常量。
 * 两端决策语义必须保持一致：硬放行词表 → '>' 强广告抬升。
 */
object FilterGuards {

    /** 硬放行词表：误杀代价极高，内容命中直接 PASSED（Plan.md §5.6 护栏） */
    val HARD_ALLOW_WORDS =
        listOf("验证码", "动态码", "校验码", "OTP", "verification code", "one-time")

    /** 强广告标记：通知内容含 ">" / ">>"（含全角 ＞）极大概率为广告，概率抬到 0.95 */
    const val SPAM_MARK_BOOST = 0.95
}
