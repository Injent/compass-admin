package ru.injent.util

import io.ktor.http.ContentType
import java.net.URLEncoder

val ZipContentType: ContentType = ContentType.parse("application/zip")
val XlsContentType: ContentType = ContentType.parse("application/vnd.ms-excel")
val XlsxContentType: ContentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

/**
 * Формирует заголовок Content-Disposition для скачивания файла с поддержкой UTF-8.
 */
fun contentDisposition(fileName: String, attachment: Boolean = true): String {
    val fallback = fileName.replace(Regex("""[^\w.\- ]"""), "_")
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
    val dispositionType = if (attachment) "attachment" else "inline"
    return """$dispositionType; filename="$fallback"; filename*=UTF-8''$encoded"""
}

/**
 * Формирует Content-Disposition для части multipart формы.
 */
fun multipartFileDisposition(name: String, fileName: String): String {
    val fallback = fileName.replace(Regex("""[^\w.\- ]"""), "_")
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
    return """form-data; name="$name"; filename="$fallback"; filename*=UTF-8''$encoded"""
}
