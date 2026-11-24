package cc.ytdttj.noticleaner

import cc.ytdttj.noticleaner.data.LearningHelper
import cc.ytdttj.noticleaner.data.db.LEARN_FOCUS_STRUCT
import cc.ytdttj.noticleaner.data.db.LEARN_FOCUS_TEXT
import cc.ytdttj.noticleaner.data.db.NotificationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学习内容迁移（2.3.0 Dev 1，schema 4 → 5）的纯决策回归：
 * 旧版「学整条通知」的标注必须被自动改写成新方式的模板学习，
 * 让升级后的用户不需要重新点一遍。
 */
class LearningMigrationTest {

    private fun row(
        id: Long,
        pkg: String = "com.sina.weibo",
        title: String = "",
        content: String = "",
        label: Int = 1,
        focus: String = "",
        kind: String = "",
        count: Int = 1,
    ) = NotificationEntity(
        id = id,
        packageName = pkg,
        appName = pkg,
        channelId = "weibo_msg_push_channel",
        channelName = "微博",
        title = title,
        content = content,
        postTime = 0L,
        adProbability = 0f,
        decision = "",
        learned = true,
        learnLabel = label,
        learnCount = count,
        learnFocus = focus,
        learnFocusKind = kind,
        expireAt = 0L,
    )

    @Test
    fun wholeTextAdWithAtAndDetailIsMigratedToStructCombo() {
        val n = row(
            id = 1,
            title = "@ZRGNiX",
            content = "你说中午想吃饺子 GPT-6 是反手给你订了10吨面粉和机...详情>>",
            count = 5,
        )
        val plan = LearningHelper.planFocusMigration(listOf(n))
        assertEquals(LEARN_FOCUS_STRUCT, plan[1L]?.kind)
        assertEquals("at+detail_gt", plan[1L]?.focus)
    }

    @Test
    fun oneSidedEvidenceIsEnoughForMigration() {
        // 单条旧标注也应迁移：用户已经点过「广告」，迁移不再要求 ≥2 条
        val n = row(id = 2, title = "@someone", content = "限时活动详情>>")
        val plan = LearningHelper.planFocusMigration(listOf(n))
        assertEquals("at+detail_gt", plan[2L]?.focus)
    }

    @Test
    fun mixedComboIsNotMigrated() {
        val ad = row(id = 3, title = "@someone", content = "限时活动详情>>", label = 1)
        val normal = row(id = 4, title = "@friend", content = "一起吃个饭详情>>", label = 0)
        val plan = LearningHelper.planFocusMigration(listOf(ad, normal))
        assertNull("同组合两侧都有标注时不得建立模板先验", plan[3L])
        assertNull(plan[4L])
    }

    @Test
    fun explicitTextFocusIsPreservedAndTyped() {
        val n = row(id = 5, title = "@someone", content = "限时活动详情>>", focus = "限时活动")
        // 即使命中组合，用户已显式选过文本重点 → 保留用户选择，只补记类型
        val plan = LearningHelper.planFocusMigration(listOf(n))
        assertEquals(LEARN_FOCUS_TEXT, plan[5L]?.kind)
        assertEquals("限时活动", plan[5L]?.focus)
    }

    @Test
    fun rowWithoutStructuralMarksStaysWholeText() {
        val n = row(id = 6, pkg = "com.example.bank", title = "动账提醒", content = "您账户支出 20 元")
        val plan = LearningHelper.planFocusMigration(listOf(n))
        assertNull("没有结构模板的标注维持整条文本学习", plan[6L])
    }

    @Test
    fun singleAtWithoutComboIsNotMigrated() {
        // 真人 @ 只有单个标记：不能自动建立 @ 单桶，否则会误伤该 App 的所有 @ 推送
        val n = row(id = 7, title = "@friend", content = "在吗")
        val plan = LearningHelper.planFocusMigration(listOf(n))
        assertNull(plan[7L])
    }

    @Test
    fun alreadyStructRowIsNotPlannedAgain() {
        val n = row(
            id = 8,
            title = "@someone",
            content = "活动详情>>",
            focus = "at+detail_gt",
            kind = LEARN_FOCUS_STRUCT,
        )
        assertNull(LearningHelper.planFocusMigration(listOf(n))[8L])
    }
}
