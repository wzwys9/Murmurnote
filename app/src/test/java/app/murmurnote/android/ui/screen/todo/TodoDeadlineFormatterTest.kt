package app.murmurnote.android.ui.screen.todo

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoDeadlineFormatterTest {

    @Test
    fun deadlineUsesTheUsersTimeZoneInsteadOfTheJvmDefaultFormatterImplicitly() {
        val deadline = Instant.parse("2026-07-11T00:30:00Z").toEpochMilli()

        assertEquals(
            "截止 7月11日",
            formatTodoDeadline(deadline, ZoneId.of("Asia/Shanghai"), Locale.CHINA),
        )
        assertEquals(
            "截止 7月10日",
            formatTodoDeadline(deadline, ZoneId.of("America/Los_Angeles"), Locale.US),
        )
    }
}
