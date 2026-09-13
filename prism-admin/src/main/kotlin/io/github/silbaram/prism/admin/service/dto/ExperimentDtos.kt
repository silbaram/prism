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
    val targetingRules: List<TargetingRuleDto> = emptyList(),
    val trafficAllocation: Int = 100,
    val startsAt: java.time.LocalDateTime? = null,
    val endsAt: java.time.LocalDateTime? = null,
    val guardrailEventNames: Set<String> = emptySet(),
    val layerKey: String? = null, val layerStart: Int? = null, val layerEnd: Int? = null,
    val stickyBucketing: Boolean = false
)

data class ExperimentUpdateDto(
    val key: String,
    val description: String,
    val goalEventName: String,
    val status: io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus,
    val variants: List<VariantDto>,
    val targetingRules: List<TargetingRuleDto> = emptyList(),
    val trafficAllocation: Int = 100,
    val startsAt: java.time.LocalDateTime? = null,
    val endsAt: java.time.LocalDateTime? = null,
    val guardrailEventNames: Set<String> = emptySet(),
    val layerKey: String? = null, val layerStart: Int? = null, val layerEnd: Int? = null,
    val stickyBucketing: Boolean = false
)
