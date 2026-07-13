package ru.injent.service.validator

import ru.injent.service.google.SheetValidator

val HeaderValidator = SheetValidator {
    rows.forEachIndexed { rowIdx, row ->
        // A1
        if (rowIdx == 0) {
            row.getOrNull(0)?.test {
                if (value.isNullOrBlank()) error("В этом месте должен быть заголовок")
            }
        }


    }
}