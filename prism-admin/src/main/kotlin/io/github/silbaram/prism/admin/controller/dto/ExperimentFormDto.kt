package io.github.silbaram.prism.admin.controller.dto

import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus

data class ExperimentFormDto(
    val key: String = "",
    val description: String = "",
    val status: ExperimentStatus = ExperimentStatus.DRAFT,
    val variants: MutableList<VariantFormDto> = mutableListOf(),
    val targetingRules: MutableList<TargetingRuleFormDto> = mutableListOf()
)

data class VariantFormDto(
    val name: String = "",
    val weight: Int = 0
)

data class TargetingRuleFormDto(
    val expression: String = ""
)
