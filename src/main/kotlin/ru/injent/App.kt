package ru.injent

import freemarker.cache.FileTemplateLoader
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.freemarker.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import ru.injent.database.databaseModule
import ru.injent.page.*
import ru.injent.service.config.AppConfig
import ru.injent.service.google.NewGoogleService
import ru.injent.service.google.googleModule
import ru.injent.service.teacher.TeacherService
import ru.injent.service.validator.LegendValidator
import ru.injent.service.validator.LessonValidator
import ru.injent.service.validator.validatorModule
import ru.injent.service.wordcorrection.WordCorrectionService
import ru.injent.service.wordcorrection.wordCorrectionModule
import java.io.File

fun Application.configureApp() {
    val appConfig = runCatching {
        environment.config.getAs<AppConfig>()
    }.getOrElse { e ->
        log.error("Can't load config. Stopping server...", e)
        engine.stop()
        return
    }

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


    install(DefaultHeaders) {
        header(HttpHeaders.CacheControl, "no-store")
    }
    install(XForwardedHeaders)
    install(Koin) {
        modules(
            module {
                single<Logger> { environment.log }
                single { appConfig }
                single<CoroutineDispatcher> { Dispatchers.IO }
                singleOf(::TeacherService)
                single<Configuration> {
                    Configuration(Configuration.VERSION_2_3_32).apply {
                        templateLoader = FileTemplateLoader(File("templates"))
                        defaultEncoding = "UTF-8"
                        templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
                        logTemplateExceptions = true
                        wrapUncheckedExceptions = true
                    }
                }
            },
            databaseModule,
            validatorModule,
            googleModule,
            wordCorrectionModule
        )
    }
    install(FreeMarker) {
        templateLoader = get<Configuration>().templateLoader
    }

    val googleService = get<NewGoogleService>()
    val wordCorrectionService = get<WordCorrectionService>()
    val teacherService = get<TeacherService>()
    val sheetValidators = listOf(get<LegendValidator>(), get<LessonValidator>())

    runBlocking {
        googleService.loadFiles()
    }

    routing {
        staticAssets()
        indexPage()
        schedulePage(googleService, wordCorrectionService, sheetValidators)
        teachersPage(teacherService)
        editorPage(googleService)
        googleSheetsCallbackPage(googleService, sheetValidators, this@configureApp)
    }
}
