package io.github.silbaram.prism.api.conversion.application.port.out

interface LoadImpressionPort {
    fun loadLatestImpression(userId: String, experimentKey: String): Impression?
}

data class Impression(val id: Long, val variantName: String)
