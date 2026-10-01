package io.github.silbaram.prism.sdk

import java.time.Duration

/** Count every capacity rejection, but warn only on the first and after a minute of further traffic. */
internal class ExposureDedupDiagnostics(
    private val nanoTime: () -> Long = System::nanoTime,
    private val warn: (total: Long, sinceLastWarning: Long) -> Unit
) {
    private var rejected = 0L
    private var warnedRejected = 0L
    private var lastWarningAt: Long? = null

    val rejectedCount: Long @Synchronized get() = rejected

    @Synchronized
    fun reject() {
        rejected++
        val now = nanoTime()
        if (lastWarningAt?.let { now - it < WARNING_INTERVAL_NANOS } == true) return
        val sinceLastWarning = rejected - warnedRejected
        lastWarningAt = now
        warnedRejected = rejected
        warn(rejected, sinceLastWarning)
    }

    private companion object {
        val WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos()
    }
}
