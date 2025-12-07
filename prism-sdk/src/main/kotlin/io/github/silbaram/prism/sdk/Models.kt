package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class AssignmentResponse(
    val userId: String,
    val experimentKey: String,
    val variant: String?,
    val resultCode: String,
    val resultMessage: String
)

data class ConversionRequest(
    val experimentKey: String,
    val userId: String,
    val eventName: String
)
