package io.github.silbaram.prism.common.rest.dto.event

import java.time.Instant
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/** Explicit opt-in metadata captured before treatment. Targeting attributes are never copied automatically. */
data class ExposureAnalysisContext @JvmOverloads constructor(
    val segments: Map<String, String> = emptyMap(),
    val baselineValue: Double? = null,
    val baselineMeasuredAt: String? = null
) {
    companion object {
        // Spring HTTP uses Jackson 3, while SDK/Kafka use Jackson 2. Explicit factory works
        // with both and avoids no-arg construction silently discarding immutable properties.
        @JvmStatic @JsonCreator
        fun fromJson(@JsonProperty("segments") segments: Map<String, String?>?,
                     @JsonProperty("baselineValue") baselineValue: Double?,
                     @JsonProperty("baselineMeasuredAt") baselineMeasuredAt: String?) =
            ExposureAnalysisContext(segments.orEmpty().mapValues { requireNotNull(it.value) { "Analysis segment values cannot be null" } },
                baselineValue, baselineMeasuredAt)
    }
    fun validate() {
        require(segments.size <= 5 && segments.all { (key, value) ->
            key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) && value.isNotBlank() && value.length <= 64
        }) { "Provide at most 5 named analysis segments with 1–64 character values" }
        require((baselineValue == null) == (baselineMeasuredAt == null)) { "Baseline value and measurement time must be supplied together" }
        baselineValue?.let { require(it.isFinite() && kotlin.math.abs(it) <= 1e9) { "Baseline must be finite and within +/- 1e9" } }
        baselineMeasuredAt?.let {
            try {
                // Instant supports a wider year range than the UTC LocalDateTime used by ingestion.
                java.time.LocalDateTime.ofInstant(Instant.parse(it), java.time.ZoneOffset.UTC)
            } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Invalid baseline measurement time") }
        }
    }
}
