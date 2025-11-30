package io.github.silbaram.prism.admin.controller.dto

import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus

data class ExperimentFormDto(
    val key: String,
    val description: String,
    val status: ExperimentStatus,
    val variants: List<VariantFormDto> = emptyList(),
    val targetingRules: List<TargetingRuleFormDto> = emptyList()
)

data class VariantFormDto(
    val name: String,
    val weight: Int
)

data class TargetingRuleFormDto(
    val expression: String
)
