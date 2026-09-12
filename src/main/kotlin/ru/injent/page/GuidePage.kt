package ru.injent.page

import io.ktor.http.HttpStatusCode
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

fun Routing.guidePage() {
    get("/guide") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
    get("/api/guide") {
        try {
            val panels = withContext(Dispatchers.IO) {
                val directory = Path.of("static", "guide")
                if (!Files.isDirectory(directory)) return@withContext emptyList<GuidePanel>()
                Files.list(directory).use { paths ->
                    paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                        .map { path ->
                            val match = GUIDE_FILE_NAME.matchEntire(path.fileName.toString())
                            val order = match?.groupValues?.get(1)?.toLongOrNull()
                            if (order == null) null else Triple(order, match.groupValues[2].trim(), path)
                        }
                        .filter { it != null && it.second.isNotEmpty() }
                        .toList()
                        .filterNotNull()
                        .sortedWith(compareBy({ it.first }, { it.second }))
                        .map { (_, title, path) -> GuidePanel(title, Files.readString(path, Charsets.UTF_8).removePrefix("\uFEFF")) }
                }
            }
            call.respond(panels)
        } catch (_: java.io.IOException) {
            call.respond(HttpStatusCode.InternalServerError, ApiError("Не удалось прочитать справку. Проверьте файлы и кодировку UTF-8."))
        }
    }
}

@Serializable
private data class GuidePanel(val title: String, val markdown: String)

private val GUIDE_FILE_NAME = Regex("""^(\d+)\.\s*(.+)\.md$""", RegexOption.IGNORE_CASE)
