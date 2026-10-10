package io.github.silbaram.prism.common.rest.dto.conversion

/** Guard historic exposures too when an application can no longer provide every treatment. */
data class SupportedConversionRequest(val userId: String, val experimentKey: String, val eventName: String,
                                      val supportedVariants: Set<String>)
