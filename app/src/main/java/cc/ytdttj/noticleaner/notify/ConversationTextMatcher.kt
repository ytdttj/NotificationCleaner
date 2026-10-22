package cc.ytdttj.noticleaner.notify

/**
 * 文本级会话识别（2.2.1 Dev 1）。
 *
 * 背景：抖音 / 闲鱼 / 小红书这类 App 的**私信**推送走的是裸 mipush 通知，
 * 既不带 `CATEGORY_MESSAGE`、也没有 `MessagingStyle` / `EXTRA_CONVERSATION_TITLE` /
 * `EXTRA_MESSAGES` —— [NotificationProtector.classifyType] 的四个"结构性"会话信号
 * **一个都不命中**，于是私信被丢给 AI 打分。
 *
 * 而 AI 对这类文本给出的分数高得离谱（2026-10-14 实测）：
 * ```
 * 私信了你: [分享视频]   → 98%
 * 私信了你: [分享图文]   → 98%
 * 私信了你: 分享[评论]   → 96%
 * 私信了你: 这样不是给自己找事吗 → 0%（放行，随内容漂移）
 * ```
 * 判别器是 `[分享视频]` / `[分享图文]` / `分享[评论]` 这些 n-gram（在广告语料里高频），
 * 结果**真人的私信被当成广告拦掉**；而且拦在模块端（`FILTERED_BY_AI_MODULE`），
 * 通知根本到不了 NLS → 历史库没这行 → 用户连「学为正常」都点不到（恶性闭环）。
 *
 * 这里用**会话句式**兜底：下列句式是 IM 私信 / 好友请求 / 群邀请的强指纹，
 * **营销推送几乎不会用**（营销用的是「@你」「拍了拍你」「新消息」「点击查看」这类）。
 * 命中即判 `DECISION_CONVERSATION`，跳过 AI ——
 * **对所有用户自动生效，不需要任何配置，也不需要用户先去学习。**
 *
 * 刻意**不**收录的弱词（营销高频，收进来会把广告一起放行）：
 * `@你`（小米音乐「希望今天的你@你 也很快乐」）、`拍了拍你`（小米音乐/网易云营销）、
 * `新消息` 单独出现（京东/得物「你有1条新消息」）、`分享`（营销也分享）。
 */
object ConversationTextMatcher {

    /** 会话强标记（去掉空白后做子串匹配） */
    private val MARKERS = listOf(
        "私信了你", // 抖音/快手：X 私信了你: [分享视频]
        "发来一条新消息", // 闲鱼/小红书/B站
        "请求添加你为朋友", // 微信/QQ/抖音
        "邀请你加入群聊", // 群邀请
        "续火花", // 抖音
        "回复了你", // 评论/私信回复
        "@了你", // 群聊 @（区别于营销的「@你」）
        "向你发起了", // 语音/视频通话邀请
    )

    /**
     * title 与 content 合并后是否命中会话强标记。
     * 合并前会去掉所有空白与零宽字符，避免"标签 / 正文之间插了空格或零宽"导致漏判。
     */
    fun isConversation(title: String?, content: String?): Boolean {
        if (title.isNullOrEmpty() && content.isNullOrEmpty()) return false
        val normalized = normalize(title, content)
        if (normalized.isEmpty()) return false
        return MARKERS.any { normalized.contains(it) }
    }

    private fun normalize(title: String?, content: String?): String {
        val sb = StringBuilder((title?.length ?: 0) + (content?.length ?: 0))
        appendNormalized(sb, title)
        appendNormalized(sb, content)
        return sb.toString()
    }

    private fun appendNormalized(sb: StringBuilder, s: String?) {
        if (s.isNullOrEmpty()) return
        for (ch in s) {
            when {
                ch.isWhitespace() -> Unit
                ch.code == 0x200B || ch.code == 0xFEFF || ch.code == 0x200E || ch.code == 0x200F -> Unit
                else -> sb.append(ch.lowercaseChar())
            }
        }
    }
}
