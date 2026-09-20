package cc.ytdttj.noticleaner.notify

/**
 * NLS 决策路径与 LSPosed 模块端（system_server 入队前拦截）共用的护栏常量。
 * 两端决策语义必须保持一致：硬放行词表 → '>' 强广告抬升。
 */
object FilterGuards {

    /** 硬放行词表：误杀代价极高，内容命中直接 PASSED（Plan.md §5.6 护栏）。
     *  保留用于诊断/展示；热路径判定请用 [HARD_ALLOW_WORDS_NORMALIZED]。 */
    val HARD_ALLOW_WORDS =
        listOf("验证码", "动态码", "校验码", "OTP", "verification code", "one-time")

    /**
     * 硬放行词表（归一化形态，1.3.2 P1-3）：供热路径对已 normalize 的文本做
     * 普通大小写敏感 contains，省去 6 次 locale 敏感的 ignoreCase 扫描。
     *
     * ⚠️ 新增词约束：normalize 会删除空白、连字符、数字、字母 'x' 与标点/符号 ——
     * 含这些字符的词改判 normalized 后会从"命中"变"漏放行"（误杀）。
     * 新增词必须满足 `normalize(词) == 词的小写形态`，并由单测（HardAllowWordsTest）
     * 逐词校验后方可合入。
     */
    val HARD_ALLOW_WORDS_NORMALIZED =
        listOf("验证码", "动态码", "校验码", "otp", "verificationcode", "onetime")

    /** 强广告标记：通知内容含 ">" / ">>"（含全角 ＞）极大概率为广告，概率抬到 0.95 */
    const val SPAM_MARK_BOOST = 0.95
}
