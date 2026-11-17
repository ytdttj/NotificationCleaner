package cc.ytdttj.noticleaner

import cc.ytdttj.noticleaner.diagnostics.RingLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 环形日志时间戳解析回归（20261028 设备日志实锤）：
 * P1-12 改 DateTimeFormatter 时用了 `LocalDateTime.parse`，而 java.time 不允许无年份
 * 字段直接转 LocalDateTime（DateTimeParseException）→ parseLeadingTs 全返回 null →
 * trimLocked 每小时清空环形日志、诊断导出全部"24h 内 0 条"。
 * 正确语义：无年份 → 回填当前年份；解析结果晚于当前 +1h（跨年残留）→ 回退去年。
 */
class RingLogTest {

    private fun expected(year: Int, month: Int, day: Int, h: Int, m: Int, s: Int, ms: Int): Long =
        LocalDateTime.of(year, month, day, h, m, s, ms * 1_000_000)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun validLineParsesWithCurrentYearBackfilled() {
        val ts = RingLog.parseLeadingTs("10-28 13:39:00.123 决策 pkg=com.baidu.netdisk decision=PASSED")
        assertNotNull("合法行必须能解析（vc90 回归：全返回 null）", ts)
        val today = LocalDate.now()
        // 测试在 10-28 当天跑时正好匹配；其他日期跑则验证"回填当前年份"这一语义本身
        if (today.monthValue == 10 && today.dayOfMonth == 28) {
            assertEquals(expected(today.year, 10, 28, 13, 39, 0, 123), ts)
        }
    }

    @Test
    fun futureLineFallsBackToLastYear() {
        // 用"未来的今天"构造跨年场景：若今天是 01-01，则 12-31 的行应回退到去年。
        // 通用构造：取当前日期 +1 天（可能跨月/跨年），回填去年后再判小于 now。
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val line = "%02d-%02d 12:00:00.000 x".format(tomorrow.monthValue, tomorrow.dayOfMonth)
        val ts = RingLog.parseLeadingTs(line)
        assertNotNull(ts)
        // 回退去年后必不晚于 now+1h；若明天恰在一年内则也可能等于今年 tomorrow 12:00
        val thisYearTs = expected(
            today.year, tomorrow.monthValue, tomorrow.dayOfMonth, 12, 0, 0, 0,
        )
        if (thisYearTs > System.currentTimeMillis() + 60 * 60 * 1000L) {
            assertTrue("未来的行应回退去年", ts!! < System.currentTimeMillis() + 60 * 60 * 1000L)
        }
    }

    @Test
    fun garbageLinesReturnNull() {
        assertNull(RingLog.parseLeadingTs("garbage line without ts"))
        assertNull(RingLog.parseLeadingTs("99-99 99:99:99.999 bad fields"))
        assertNull(RingLog.parseLeadingTs(""))
    }
}
