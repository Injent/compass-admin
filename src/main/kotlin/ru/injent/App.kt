package ru.injent

import freemarker.cache.FileTemplateLoader
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.resources.*
import io.ktor.server.sse.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory
import ru.injent.service.google.NewGoogleService
import ru.injent.service.google.googleModule
import ru.injent.service.validator.LegendValidator
import java.io.File

fun Application.configureApp() {
    install(SSE)
    install(ContentNegotiation) {
        json()
    }
    install(Resources)
    install(CORS) {
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Delete)

        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Origin)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)

        anyHost()
        allowCredentials = true
    }
    install(XForwardedHeaders)
    install(FreeMarker) {
        templateLoader = Configuration(Configuration.VERSION_2_3_32).apply {
            templateLoader = FileTemplateLoader(File("templates"))
            defaultEncoding = "UTF-8"
            templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
            logTemplateExceptions = true
            wrapUncheckedExceptions = true
        }.templateLoader
    }
    install(Koin) {
        modules(
            module {
                single { LoggerFactory.getLogger("CompassAdmin") }
                single<CoroutineDispatcher> { Dispatchers.IO }
            },
            googleModule
        )
    }
    runBlocking {
        get<NewGoogleService>().test(
            listOf(LegendValidator)
        )
    }
}
