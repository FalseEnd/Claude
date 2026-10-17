package tw.xiaoliangkou.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.xiaoliangkou.app.core.Dates
import tw.xiaoliangkou.app.data.ProfileInfo
import tw.xiaoliangkou.app.ui.UNPAIRED_ROUTES
import java.time.LocalDate

/** 第二輪審查修正：不用畫面的部分 */
class ReviewFix2Test {
    /** 2/29 出生：年齡和生日倒數在同一天加一歲（平年是 2/28），畫面上不會一邊「24 歲」一邊「今天滿 25 歲」 */
    @Test fun leapDayAgeMatchesBirthdayCountdown() {
        val birthday = LocalDate.of(2000, 2, 29)
        var day = LocalDate.of(2000, 2, 29)
        val end = LocalDate.of(2033, 3, 2)
        while (!day.isAfter(end)) {
            val next = Dates.nextOccurrence(birthday, day, yearly = true)
            if (next.daysLeft == 0) assertEquals("$day", next.years, ProfileInfo.age(birthday, day))
            else assertEquals("$day", next.years - 1, ProfileInfo.age(birthday, day))
            day = day.plusDays(1)
        }
        assertEquals(24, ProfileInfo.age(birthday, LocalDate.of(2025, 2, 27)))
        assertEquals(25, ProfileInfo.age(birthday, LocalDate.of(2025, 2, 28)))
        assertEquals(25, ProfileInfo.age(birthday, LocalDate.of(2025, 3, 1)))
        assertEquals(27, ProfileInfo.age(birthday, LocalDate.of(2027, 12, 31)))
        assertEquals(28, ProfileInfo.age(birthday, LocalDate.of(2028, 2, 29)))
        assertEquals(27, ProfileInfo.age(birthday, LocalDate.of(2028, 2, 28)))
    }

    /** 一般的生日也和倒數一致 */
    @Test fun ageMatchesCountdownForOrdinaryBirthdays() {
        val birthday = LocalDate.of(1999, 12, 31)
        var day = LocalDate.of(2024, 12, 25)
        while (day.isBefore(LocalDate.of(2026, 1, 5))) {
            val next = Dates.nextOccurrence(birthday, day, yearly = true)
            assertEquals("$day", if (next.daysLeft == 0) next.years else next.years - 1, ProfileInfo.age(birthday, day))
            day = day.plusDays(1)
        }
    }

    /** 備忘錄小工具點了開「memos」：沒配對時也要打得開 */
    @Test fun memoWidgetRouteWorksWhileUnpaired() {
        assertTrue("memos" in UNPAIRED_ROUTES)
        assertTrue("profile" in UNPAIRED_ROUTES && "fortune" in UNPAIRED_ROUTES)
    }
}
