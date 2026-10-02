package io.github.silbaram.prism.admin.controller.dto

import java.time.LocalDateTime
import java.time.format.DateTimeParseException

private val nativeDateTime = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,3})?)?")

/** Decide from the actual input string: a parsed .123000 is milliseconds, but browsers discard that spelling. */
internal fun utcDateTimeInputType(value: String): String {
    if (value.isEmpty()) return "datetime-local"
    if (!nativeDateTime.matches(value)) return "text"
    val time = try { LocalDateTime.parse(value) } catch (_: DateTimeParseException) { return "text" }
    return if (time.year > 0) "datetime-local" else "text"
}
