package ru.injent.service.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    @SerialName("gigachat")
    val gigachat: GigaChatConfig,
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