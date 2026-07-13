package ru.injent.service.wordcorrection

import chat.giga.client.GigaChatClient
import chat.giga.model.completion.ChatMessage
import chat.giga.model.completion.ChatMessageRole
import chat.giga.model.completion.CompletionRequest
import io.ktor.util.logging.*
import ru.injent.service.config.AppConfig
import java.io.File

class WordCorrectionService(
    private val gigaChat: GigaChatClient,
    private val logger: Logger,
    private val config: AppConfig
) {

    private val systemInstructions: String by lazy {
        File(config.gigachat.systemInstructionsFilePath).readText()
    }

    fun correctWords(input: Map<Int, String>): Map<Int, String> {
        if (input.isEmpty()) return emptyMap()

        var lastError: Throwable? = null
        repeat(config.gigachat.maxRetries.coerceAtLeast(1)) { attempt ->
            try {
                val response = gigaChat.completions(
                    CompletionRequest.builder()
                        .model(config.gigachat.model)
                        .message(
                            ChatMessage.builder()
                                .role(ChatMessageRole.SYSTEM)
                                .content(systemInstructions)
                                .build()
                        )
                        .message(
                            ChatMessage.builder()
                                .role(ChatMessageRole.USER)
                                .content(input.toCorrectionPrompt())
                                .build()
                        )
                        .temperature(0f)
                        .build()
                )

                val content = response.choices()
                    .firstOrNull()
                    ?.message()
                    ?.content()
                    ?: error("GigaChat response does not contain message content")

                return parseCorrections(content, input.keys)
            } catch (error: Throwable) {
                lastError = error
                logger.warn("word correction attempt ${attempt + 1} failed", error)
            }
        }

        throw IllegalStateException("Word correction failed after ${config.gigachat.maxRetries} attempts", lastError)
    }

    private fun Map<Int, String>.toCorrectionPrompt(): String =
        entries.joinToString(
            separator = "\n",
            prefix = "Ввод осуществляется по ключам по строкам\nключ=значение\n",
            postfix = "\n\nВывод\nключ=значение"
        ) { (key, value) ->
            "$key=${value.replace("\r", " ").replace("\n", " ")}"
        }

    private fun parseCorrections(content: String, allowedKeys: Set<Int>): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        val lines = content
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)

        lines.forEach { line ->
            val separatorIdx = line.indexOf('=')
            if (separatorIdx <= 0) {
                error("Invalid correction line: '$line'")
            }

            val key = line.substring(0, separatorIdx).trim().toIntOrNull()
                ?: error("Invalid correction key: '$line'")
            val value = line.substring(separatorIdx + 1).trim()

            if (key !in allowedKeys) {
                error("Unknown correction key: '$key'")
            }
            if (value.isBlank()) {
                error("Correction for key '$key' is blank")
            }

            result[key] = value
        }
        return result
    }
}
