package io.github.silbaram.prism.api.conversion.application.port.`in`

interface TrackConversionUseCase {
    fun trackConversion(command: TrackConversionCommand): TrackConversionResult
}

data class TrackConversionCommand(val userId: String, val experimentKey: String, val eventName: String) {
    init {
        require(userId.isNotBlank() && userId.length <= 255) { "userId must contain 1–255 characters" }
        require(experimentKey.isNotBlank() && experimentKey.length <= 255) { "experimentKey must contain 1–255 characters" }
        require(eventName.isNotBlank() && eventName.length <= 255) { "eventName must contain 1–255 characters" }
    }
}

sealed interface TrackConversionResult {
    val command: TrackConversionCommand
    data class Recorded(override val command: TrackConversionCommand, val variantName: String) : TrackConversionResult
    data class Rejected(override val command: TrackConversionCommand) : TrackConversionResult
}
