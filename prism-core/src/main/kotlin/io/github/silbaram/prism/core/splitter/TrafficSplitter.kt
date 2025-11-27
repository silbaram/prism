package io.github.silbaram.prism.core.splitter

import io.github.silbaram.prism.core.hashing.MurmurHash
import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.core.targeting.RuleEvaluator
import io.github.silbaram.prism.core.targeting.UserContext
import kotlin.math.abs

object TrafficSplitter {
    
    fun assign(experiment: Experiment, userId: String, context: UserContext = UserContext(emptyMap())): Variant? {
        // Check targeting rules
        if (experiment.targetingRules.isNotEmpty()) {
            val isTargeted = experiment.targetingRules.all { rule ->
                RuleEvaluator.evaluate(rule, context)
            }
            if (!isTargeted) {
                return null // Not in target audience
            }
        }

        // Combine experiment key and user ID to ensure different assignments across experiments
        val hashKey = "${experiment.key}:$userId"
        val hash = MurmurHash.hash32(hashKey)
        
        // Normalize hash to 0-99 range
        val bucket = abs(hash) % 100
        
        var currentWeight = 0
        for (variant in experiment.variants) {
            currentWeight += variant.weight
            if (bucket < currentWeight) {
                return variant
            }
        }
        
        // Should not reach here if weights sum to 100
        return experiment.variants.last()
    }
}
