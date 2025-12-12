package io.github.silbaram.prism.common.rest.dto.conversion

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConversionResponse(
    val userId: String,
    val experimentKey: String,
    val eventName: String,
    val variant: String?,
    val resultCode: String,
    val resultMessage: String
)
