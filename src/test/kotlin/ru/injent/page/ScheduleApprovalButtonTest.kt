package ru.injent.page

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleApprovalButtonTest {
    @Test
    fun `approval state follows initial loading state`() {
        val loadingState = scheduleView(files = emptyList(), filesLoaded = false)
        val readyState = scheduleView(files = emptyList(), filesLoaded = true)

        assertFalse(loadingState.canOpenScheduleApproval)
        assertTrue(readyState.canOpenScheduleApproval)
    }
}
