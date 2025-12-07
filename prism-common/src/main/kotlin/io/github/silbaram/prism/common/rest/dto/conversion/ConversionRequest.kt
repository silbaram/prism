package io.github.silbaram.prism.common.rest.dto.conversion

data class ConversionRequest(
    val experimentKey: String,
    val userId: String,
    val eventName: String
)
