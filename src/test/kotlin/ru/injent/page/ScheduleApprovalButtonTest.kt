package ru.injent.page

import freemarker.template.Configuration
import java.io.File
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduleApprovalButtonTest {
    @Test
    fun `approval button is always rendered and follows initial loading state`() {
        val loadingButton = renderButton(filesLoaded = false)
        val readyButton = renderButton(filesLoaded = true)

        assertTrue(loadingButton.contains("disabled"))
        assertFalse(readyButton.contains("disabled"))
    }

    private fun renderButton(filesLoaded: Boolean): String {
        val configuration = Configuration(Configuration.VERSION_2_3_32).apply {
            setDirectoryForTemplateLoading(File("templates"))
            defaultEncoding = "UTF-8"
        }
        val output = StringWriter()
        configuration.getTemplate("schedule/schedule_list_container.html")
            .process(scheduleModel(files = emptyList(), filesLoaded = filesLoaded), output)

        return assertNotNull(
            Regex("""<m3e-button id="schedule-approve-button"[\s\S]*?>""")
                .find(output.toString())
                ?.value
        )
    }
}
