package io.github.silbaram.prism.common.rest.dto.assign

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class AssignmentResponse @JvmOverloads constructor(
    val userId: String,
    val experimentKey: String,
    val variant: String?,
    val resultCode: String,
    val resultMessage: String,
    val configVersion: String? = null,
    val exposureEventId: String? = null
)
