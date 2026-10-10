package io.github.silbaram.prism.common.rest.dto.assign

/** Supported names describe available application strategies, not a replacement traffic allocation. */
data class SupportedAssignmentRequest(val userId: String, val experimentKey: String, val supportedVariants: Set<String>)

fun validateSupportedVariants(names: Set<String>) {
    require(names.size in 1..1000 && names.all { it.isNotBlank() && it.length <= 255 }) {
        "supportedVariants must contain 1–1000 distinct names of 1–255 characters"
    }
}
