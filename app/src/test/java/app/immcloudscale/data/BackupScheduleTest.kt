package app.immcloudscale.data

import app.immcloudscale.data.backup.BackupWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class BackupScheduleTest {
    // 2026-11-25 is a Wednesday (day 3).
    private val wedNoon = LocalDateTime.of(2026, 11, 25, 12, 0)

    @Test fun laterTodayWhenTodayIsChosenAndTheTimeIsAhead() {
        assertEquals(Duration.ofHours(10), BackupWorker.untilNextRun(setOf(3), 22 * 60, wedNoon))
    }

    @Test fun nextChosenDayWhenTodaysTimeHasPassed() {
        // Wednesday 02:00 has passed; next is Friday 02:00.
        assertEquals(Duration.ofHours(38), BackupWorker.untilNextRun(setOf(3, 5), 2 * 60, wedNoon))
    }

    @Test fun sameDayNextWeekWhenItsTheOnlyDay() {
        assertEquals(Duration.ofDays(7).minusHours(1), BackupWorker.untilNextRun(setOf(3), 11 * 60, wedNoon))
    }

    @Test fun wrapsFromSundayToMonday() {
        val sunNight = LocalDateTime.of(2026, 11, 29, 23, 30)
        assertEquals(Duration.ofMinutes(60), BackupWorker.untilNextRun(setOf(1), 30, sunNight))
    }
}
