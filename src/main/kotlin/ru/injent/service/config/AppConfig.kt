package ru.injent.service.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("gigachat")
    val gigachat: GigaChatConfig,
    @SerialName("compassapi")
    val compassApiConfig: CompassApiConfig,
    @SerialName("access")
    val access: List<Access>,
    @SerialName("google-callback-api-key")
    val googleCallbackApiKey: String = "gcb_eb4bb3edc86f4b8095fdf84e13ee991aff5356ccc88b4820a036a5b45cfddcdcdbc39e19a0354a39bee81d20dccfb7d8"
)

@Serializable
data class GigaChatConfig(
    @SerialName("authkey")
    val authKey: String,
    @SerialName("system-instructions-file")
    val systemInstructionsFilePath: String,
    @SerialName("max-retries")
    val maxRetries: Int,
    @SerialName("model")
    val model: String
)

@Serializable
data class CompassApiConfig(
    @SerialName("host")
    val host: String,
    @SerialName("apikey")
    val apiKey: String
)

@Serializable
data class Access(
    @SerialName("role")
    val role: Role = Role.USER,
    @SerialName("login")
    val login: String,
    @SerialName("password")
    val password: String
) {
    enum class Role {
        SUPERUSER,
        USER
    }
}
