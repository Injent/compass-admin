package ru.injent

import freemarker.cache.FileTemplateLoader
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.config.getAs
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.util.logging.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import ru.injent.database.databaseModule
import ru.injent.page.authPage
import ru.injent.page.configPage
import ru.injent.page.editorPage
import ru.injent.page.googleSheetsCallbackPage
import ru.injent.page.indexPage
import ru.injent.page.installAuthGuard
import ru.injent.page.schedulePage
import ru.injent.page.staticAssets
import ru.injent.page.teachersPage
import ru.injent.service.auth.AuthService
import ru.injent.service.config.AppConfig
import ru.injent.service.config.RemoteConfigService
import ru.injent.service.google.NewGoogleService
import ru.injent.service.google.googleModule
import ru.injent.service.teacher.TeacherService
import ru.injent.service.validator.LegendValidator
import ru.injent.service.validator.LessonValidator
import ru.injent.service.validator.TeacherValidator
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
        allowHeader("X-API-Key")
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
                single {
                    HttpClient(CIO) {
                        expectSuccess = true
                        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                            json()
                        }
                    }
                }
                singleOf(::TeacherService)
                singleOf(::AuthService)
                singleOf(::RemoteConfigService)
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

    val authService = get<AuthService>()
    installAuthGuard(authService, appConfig.googleCallbackApiKey)

    val googleService = get<NewGoogleService>()
    val wordCorrectionService = get<WordCorrectionService>()
    val httpClient = get<HttpClient>()
    val teacherService = get<TeacherService>()
    val remoteConfigService = get<RemoteConfigService>()
    val sheetValidators = listOf(get<LegendValidator>(), get<LessonValidator>(), get<TeacherValidator>())

    launch {
        googleService.loadFiles()
    }
    routing {
        staticAssets()
        authPage(authService)
        indexPage()
        schedulePage(googleService, wordCorrectionService, sheetValidators, appConfig, httpClient, this@configureApp, log)
        teachersPage(teacherService)
        configPage(remoteConfigService)
        editorPage(googleService)
        googleSheetsCallbackPage(googleService, sheetValidators, this@configureApp)
    }
}
