package ru.injent.domain

/**
 * Статус обработки файла расписания в хранилище.
 */
enum class FileStatus {
    EMPTY,
    PROCESSING,
    VALID,
    INVALID
}
