package ru.injent.service.config

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class RemoteConfigService(
    private val config: AppConfig,
    private val client: HttpClient
) {
    suspend fun get(): RemoteConfig =
        client.get(remoteConfigUrl()).body()

    suspend fun update(remoteConfig: RemoteConfig) {
        client.post(remoteConfigUrl()) {
            header(HttpHeaders.Authorization, "Bearer ${config.compassApiConfig.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(remoteConfig)
        }
    }

    private fun remoteConfigUrl(): String =
        config.compassApiConfig.host.trimEnd('/') + "/remoteConfig"
}

@Serializable
data class RemoteConfig(
    @SerialName("termStartDate")
    val termStartDate: String,
    @SerialName("swapWeeks")
    val swapWeeks: Boolean,
    @SerialName("lastResetTimestamp")
    val lastResetTimestamp: String,
    @SerialName("versionCode")
    val versionCode: Int,
    @SerialName("downloadUrl")
    val downloadUrl: String,
    @SerialName("vkLinkSupport")
    val vkLinkSupport: String?,
    @SerialName("maxLinkSupport")
    val maxLinkSupport: String?,
    @SerialName("telegramLinkSupport")
    val telegramLinkSupport: String?,
    @SerialName("teacherSearchWarningDateRanges")
    val teacherSearchWarningDateRanges: List<List<String>>,
)
