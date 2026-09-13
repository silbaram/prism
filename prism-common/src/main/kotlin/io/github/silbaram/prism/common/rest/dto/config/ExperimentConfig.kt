package io.github.silbaram.prism.common.rest.dto.config

data class ConfigResponse(val version: String, val experiments: List<ExperimentConfig>, val holdout: HoldoutConfig = HoldoutConfig(),
                          val revision: Long = 0)
data class HoldoutConfig(val key: String = "global-v1", val basisPoints: Int = 0, val configured: Boolean = false)
data class LayerConfig(val key: String, val start: Int, val end: Int)

data class ExperimentConfig(
    val key: String,
    val status: String,
    val variants: List<VariantConfig>,
    val targetingRules: List<String> = emptyList(),
    val goalEventName: String? = null,
    val trafficAllocation: Int = 100,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val layer: LayerConfig? = null,
    val stickyBucketing: Boolean = false
)

data class VariantConfig(val name: String, val weight: Int)
