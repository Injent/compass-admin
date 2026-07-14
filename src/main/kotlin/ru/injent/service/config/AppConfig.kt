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
    val access: List<Access>
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