package io.github.silbaram.prism.api.traffic.application.port.out

interface RecordImpressionPort {
    fun recordImpression(experimentKey: String, variantName: String, userId: String)
}
