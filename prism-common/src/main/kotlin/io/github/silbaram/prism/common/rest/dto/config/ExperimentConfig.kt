package io.github.silbaram.prism.common.rest.dto.config

data class ConfigResponse(val version: String, val experiments: List<ExperimentConfig>)

data class ExperimentConfig(
    val key: String,
    val status: String,
    val variants: List<VariantConfig>,
    val targetingRules: List<String> = emptyList(),
    val goalEventName: String? = null
)

data class VariantConfig(val name: String, val weight: Int)
