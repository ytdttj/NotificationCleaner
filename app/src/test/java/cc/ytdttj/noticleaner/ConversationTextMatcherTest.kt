package cc.ytdttj.noticleaner

import cc.ytdttj.noticleaner.notify.ConversationTextMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本级会话识别回归（2.2.1 Dev 1）。
 *
 * 语料全部取自 2026-10-14 的 24 小时真实日志（17PM / 2.2.0）：
 * 正例 = 被 AI 误拦的真人私信；负例 = 同一批日志里被判对/判错的营销推送。
 */
class ConversationTextMatcherTest {

    // ---- 正例：真人私信 / 好友请求 / 群邀请 ----

    @Test
    fun douyinPrivateMessagesAreConversation() {
        // 这四种就是被 AI 判到 98% / 98% / 96% / 0% 的那几条
        assertTrue(ConversationTextMatcher.isConversation("糖糖包老婆🥰🥰", "私信了你: [分享视频]"))
        assertTrue(ConversationTextMatcher.isConversation("糖糖包老婆🥰🥰", "私信了你: [分享图文]"))
        assertTrue(ConversationTextMatcher.isConversation("王钧博", "私信了你: 分享[评论]"))
        assertTrue(ConversationTextMatcher.isConversation("付骢", "私信了你: 这样不是给自己找事吗"))
    }

    @Test
    fun friendRequestAndGroupInviteAreConversation() {
        assertTrue(ConversationTextMatcher.isConversation("伊瑞斯阀门-张茹茹", "请求添加你为朋友"))
        assertTrue(ConversationTextMatcher.isConversation("付骢", "和你一起续火花"))
        assertTrue(ConversationTextMatcher.isConversation("群助手", "邀请你加入群聊「家人群」"))
        assertTrue(ConversationTextMatcher.isConversation("宋瑶", "回复了你：好的"))
        assertTrue(ConversationTextMatcher.isConversation("糖糖炖蛋", "@了你 一下"))
        assertTrue(ConversationTextMatcher.isConversation("李四", "向你发起了语音通话"))
    }

    // ---- 负例：营销推送不能被放行 ----

    @Test
    fun marketingPushesAreNotConversation() {
        // 小米音乐（AI 判 95% 拦掉，属于正确拦截）
        assertFalse(ConversationTextMatcher.isConversation("づ♡ど 拍了拍你", "并为你点亮 心动频率>"))
        assertFalse(ConversationTextMatcher.isConversation("希望今天的你@你 也很快乐", "用日推点亮生活>>"))
        assertFalse(ConversationTextMatcher.isConversation("♡回忆杀速递♡", "@你，不体验一下太可惜了吧~"))
        assertFalse(ConversationTextMatcher.isConversation("可恶，藏不住了", "@你，快来一键开启歌单宝箱>>"))
    }

    @Test
    fun ecommerceAndLogisticsPushesAreNotConversation() {
        assertFalse(
            ConversationTextMatcher.isConversation(
                "【新消息】亲爱的ytdttj_",
                "诺恒数码还在焦急的等待您的回复哦～",
            ),
        )
        assertFalse(
            ConversationTextMatcher.isConversation(
                "【订单物流】「中通速递」物流服务通知",
                "您寄往温州市的快递已发出，点击查看详情>>",
            ),
        )
        assertFalse(
            ConversationTextMatcher.isConversation(
                "叶航克，你有1条新消息",
                "您好，我们是教育行业TOP品牌，诚招学习规划师",
            ),
        )
    }

    // ---- 边界 ----

    @Test
    fun blankInputIsNotConversation() {
        assertFalse(ConversationTextMatcher.isConversation(null, null))
        assertFalse(ConversationTextMatcher.isConversation("", ""))
        assertFalse(ConversationTextMatcher.isConversation("   ", "  \n "))
    }

    @Test
    fun whitespaceAndZeroWidthDoNotBreakMatching() {
        assertTrue(ConversationTextMatcher.isConversation("糖糖包", "私信 了你：[分享视频]"))
        assertTrue(ConversationTextMatcher.isConversation("王钧博", "私\u200B信\u200B了\u200B你: [分享视频]"))
        // 标记可以只出现在标题里
        assertTrue(ConversationTextMatcher.isConversation("王钧博 私信了你", null))
    }
}
