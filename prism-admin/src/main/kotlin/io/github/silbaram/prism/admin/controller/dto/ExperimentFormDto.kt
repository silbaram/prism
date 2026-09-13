package io.github.silbaram.prism.admin.controller.dto

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

@ValidExperimentWeights
data class ExperimentFormDto(
    val key: String = "",
    @field:Size(max = 255, message = "설명은 255자 이하여야 합니다.") val description: String = "",
    val goalEventName: String = "",
    val status: ExperimentStatus = ExperimentStatus.DRAFT,
    val variants: MutableList<VariantFormDto> = mutableListOf(),
    val targetingRules: MutableList<TargetingRuleFormDto> = mutableListOf(),
    @field:Min(0, message = "참여 비율은 0–100%여야 합니다.")
    @field:Max(100, message = "참여 비율은 0–100%여야 합니다.") val trafficAllocation: Int = 100,
    val startsAt: String = "",
    val endsAt: String = "",
    val guardrailEvents: String = ""
)

data class VariantFormDto(
    val name: String = "",
    val weight: Int = 0
)

data class TargetingRuleFormDto(
    val expression: String = ""
)
