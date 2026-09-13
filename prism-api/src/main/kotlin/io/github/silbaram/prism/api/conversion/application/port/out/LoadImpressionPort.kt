package io.github.silbaram.prism.api.conversion.application.port.out

interface LoadImpressionPort {
    fun loadLatestImpression(userId: String, experimentKey: String): Impression?
    fun loadLatestOccurredImpression(userId: String, experimentKey: String): Impression?
}

data class Impression(val id: Long, val variantName: String, val eventId: String? = null, val configVersion: String? = null)
