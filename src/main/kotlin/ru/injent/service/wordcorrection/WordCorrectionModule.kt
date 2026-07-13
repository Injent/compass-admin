package ru.injent.service.wordcorrection

import chat.giga.client.GigaChatClient
import chat.giga.client.auth.AuthClient
import chat.giga.client.auth.AuthClientBuilder.OAuthBuilder
import chat.giga.model.Scope
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import ru.injent.service.config.AppConfig

val wordCorrectionModule = module {
    single<GigaChatClient> {
        val authKey = get<AppConfig>().gigachat.authKey

        GigaChatClient.builder()
            .verifySslCerts(false)
            .authClient(
                AuthClient.builder()
                    .withOAuth(
                        OAuthBuilder.builder()
                            .scope(Scope.GIGACHAT_API_PERS)
                            .authKey(authKey)
                            .build()
                    )
                    .build()
            )
            .build()
    }
    singleOf(::WordCorrectionService)
}