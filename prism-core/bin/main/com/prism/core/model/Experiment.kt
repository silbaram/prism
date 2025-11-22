
package com.prism.core.model

import com.prism.core.targeting.TargetingRule

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
