package io.github.silbaram.prism.api.conversion.application.port.out

import io.github.silbaram.prism.core.model.Variant

/** Includes paused/ended designs so valid late conversions remain attributable. */
interface LoadConversionVariantsPort {
    fun loadConversionVariants(experimentKey: String): List<Variant>?
}
