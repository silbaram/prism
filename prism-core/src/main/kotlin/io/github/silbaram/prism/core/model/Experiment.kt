package io.github.silbaram.prism.core.model

import io.github.silbaram.prism.core.targeting.TargetingRule

data class Experiment(
    val key: String,
    val variants: List<Variant>,
    val targetingRules: List<TargetingRule> = emptyList()
) {
    init {
        val totalWeight = variants.sumOf { it.weight }
        require(totalWeight == 100) { "Total weight must be 100" }
    }
}
