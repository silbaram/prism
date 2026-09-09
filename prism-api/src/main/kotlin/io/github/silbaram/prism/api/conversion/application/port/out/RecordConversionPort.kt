package io.github.silbaram.prism.api.conversion.application.port.out

interface RecordConversionPort {
    fun recordConversion(experimentKey: String, userId: String, eventName: String, impression: Impression)
}
