package io.github.silbaram.prism.core.model

import io.github.silbaram.prism.core.hashing.MurmurHash
import kotlin.math.abs

/** Separate hash domains keep layer membership independent of variant allocation. */
fun populationBucket(domain: String, key: String, userId: String): Int =
    (abs(MurmurHash.hash32("prism:$domain:${key.length}:$key:$userId").toLong()) % 10_000).toInt()

data class LayerAllocation(val key: String, val start: Int, val end: Int) {
    init {
        require(key.isNotBlank() && key.length <= 255)
        require(start in 0 until end && end <= 10_000) { "Layer range must satisfy 0 <= start < end <= 10000" }
    }
    fun includes(userId: String) = populationBucket("layer", key, userId) in start until end
}

data class HoldoutPolicy(val key: String = "global-v1", val basisPoints: Int = 0) {
    init { require(key.isNotBlank() && key.length <= 255 && basisPoints in 0..10_000) }
    fun excludes(userId: String) = populationBucket("holdout", key, userId) < basisPoints
}
