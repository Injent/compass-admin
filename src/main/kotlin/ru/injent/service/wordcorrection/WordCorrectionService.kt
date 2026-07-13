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
        entries.joinToString(separator = "\n") { (key, value) ->
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
            val value = line.substring(separatorIdx + 1).trim().normalizeLessonCorrection()

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

    private fun String.normalizeLessonCorrection(): String {
        var result = trim()
            .replace(WHITESPACE_REGEX, " ")
            .replace(SPACES_BEFORE_CLOSING_BRACKET_REGEX, ")")
            .replace(SPACES_AFTER_OPENING_BRACKET_REGEX, "(")
            .replace(SPACES_AROUND_ROOM_SIGN_REGEX, " №")
            .replace(BROKEN_ROOM_SIGN_REGEX, "№")
            .replace(MISSING_SPACE_BEFORE_TEACHER_REGEX, "$1 (")
            .removeTrailingDotAfterClosedBracket()

        result = result.normalizeTeacherBrackets()
        result = result.normalizeTeacherInitials()

        return result.trim()
    }

    private fun String.normalizeTeacherBrackets(): String {
        val openedIdx = lastIndexOf('(')
        val closedIdx = lastIndexOf(')')
        if (openedIdx >= 0 && closedIdx > openedIdx) return this

        val match = TRAILING_TEACHER_WITH_ONLY_CLOSING_BRACKET_REGEX.find(this) ?: return this
        return replaceRange(match.range, "(${match.groupValues[1]})")
    }

    private fun String.normalizeTeacherInitials(): String =
        replace(TEACHER_BRACKETS_REGEX) { match ->
            val teacher = match.groupValues[1].trim().replace(WHITESPACE_REGEX, " ")
            val parts = teacher.split(" ", limit = 2)
            if (parts.size != 2) return@replace match.value

            val initials = parts[1]
                .replace(".", "")
                .replace(" ", "")
                .takeIf { rawInitials -> rawInitials.length in 1..2 && rawInitials.all(Char::isLetter) }
                ?.map { initial -> "$initial." }
                ?.joinToString("")
                ?: return@replace match.value

            "(${parts[0]} $initials)"
        }

    private fun String.removeTrailingDotAfterClosedBracket(): String =
        if (endsWith(").")) dropLast(1) else this
}

private val WHITESPACE_REGEX = Regex("\\s+")
private val SPACES_BEFORE_CLOSING_BRACKET_REGEX = Regex("\\s+\\)")
private val SPACES_AFTER_OPENING_BRACKET_REGEX = Regex("\\(\\s+")
private val SPACES_AROUND_ROOM_SIGN_REGEX = Regex("\\s*№\\s+")
private val BROKEN_ROOM_SIGN_REGEX = Regex("№[/\\\\]+")
private val MISSING_SPACE_BEFORE_TEACHER_REGEX = Regex("([^\\s(])\\(")
private val TEACHER_BRACKETS_REGEX = Regex("\\(([^()]*)\\)")
private val TRAILING_TEACHER_WITH_ONLY_CLOSING_BRACKET_REGEX =
    Regex("([А-ЯЁ][А-ЯЁа-яё'\\-]+\\s+[А-ЯЁ](?:\\.?\\s*[А-ЯЁ]\\.?)?)\\)$")
