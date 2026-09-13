package io.github.silbaram.prism.core.model

/** Shared by admin writes, configuration export and SDK snapshot validation. */
fun validateExperimentIdentities(key: String, variantNames: List<String>) {
    require(key.isNotBlank() && key.length <= 255) { "Experiment key must contain 1-255 characters" }
    require(variantNames.all { it.isNotBlank() && it.length <= 255 }) {
        "Variant names must contain 1-255 characters"
    }
    require(variantNames.toSet().size == variantNames.size) { "Variant names must be distinct" }
}
