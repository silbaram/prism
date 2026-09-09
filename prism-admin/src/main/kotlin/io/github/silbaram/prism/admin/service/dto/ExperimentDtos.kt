package io.github.silbaram.prism.admin.service.dto

data class VariantDto(
    val name: String,
    val weight: Int
)

data class TargetingRuleDto(
    val expression: String
)

data class ExperimentCreateDto(
    val key: String,
    val description: String,
    val goalEventName: String,
    val variants: List<VariantDto>,
    val targetingRules: List<TargetingRuleDto> = emptyList()
)

data class ExperimentUpdateDto(
    val key: String,
    val description: String,
    val goalEventName: String,
    val status: io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus,
    val variants: List<VariantDto>,
    val targetingRules: List<TargetingRuleDto> = emptyList()
)
