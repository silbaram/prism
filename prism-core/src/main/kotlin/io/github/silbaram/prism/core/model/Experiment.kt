package io.github.silbaram.prism.core.model

import io.github.silbaram.prism.core.targeting.TargetingRule

data class Experiment(
    val key: String,
    val variants: List<Variant>,
    val targetingRules: List<TargetingRule> = emptyList()
) {
    init {
        validateVariantWeights(variants.map { it.weight })
    }
}

/** Shared by domain construction and admin create/update validation. */
fun validateVariantWeights(weights: List<Int>) {
    val totalWeight = weights.sumOf { it.toLong() }
    if (totalWeight != 100L || weights.any { it !in 0..100 }) {
        throw InvalidVariantWeightsException(totalWeight)
    }
}

class InvalidVariantWeightsException(val totalWeight: Long) : IllegalArgumentException(
    "Variant weights must be between 0 and 100 and total 100 (was $totalWeight)")
