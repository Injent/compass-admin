package ru.injent.web.route

import freemarker.template.Configuration
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import org.koin.ktor.ext.get
import ru.injent.service.auth.AuthService
import ru.injent.service.config.Access
import ru.injent.web.dto.normalizeScheduleFilter
import java.io.StringWriter

/**
 * Рендерит Freemarker шаблон по имени и контекстной модели.
 */
context(routing: Routing)
fun renderTemplate(templateName: String, model: Map<String, Any?>): String {
    val writer = StringWriter()
    routing.get<Configuration>().getTemplate(templateName).process(model, writer)
    return writer.toString()
}

/**
 * Создает модель для главного шаблона index.html.
 */
fun indexModel(call: ApplicationCall): Map<String, Any> =
    call.application.get<AuthService>().let { authService ->
        val canAccessConfig = authService.authenticate(call)?.role == Access.Role.SUPERUSER
        mapOf(
            "initialContentUrl" to call.initialContentUrl(canAccessConfig),
            "canAccessConfig" to canAccessConfig,
        )
    }

private fun ApplicationCall.initialContentUrl(canAccessConfig: Boolean): String {
    if (request.path().startsWith("/teachers")) {
        return "/teachers"
    }
    if (canAccessConfig && request.path().startsWith("/config")) {
        return "/config"
    }

    val filter = (request.queryParameters["f"] ?: request.queryParameters["filter"] ?: "all")
        .normalizeScheduleFilter()
    return if (filter == "all") "/schedule" else "/schedule?f=$filter"
}
