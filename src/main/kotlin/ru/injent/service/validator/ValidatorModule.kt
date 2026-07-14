package ru.injent.service.validator

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val validatorModule = module {
    singleOf(::LegendValidator)
    singleOf(::LessonValidator)
}
