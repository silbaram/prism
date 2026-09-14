package io.github.silbaram.prism.sdk

import java.time.Duration

enum class EvaluationMode { LOCAL, REMOTE }

/** Memory buffering is bounded and best effort; close/flush have a total time budget. */
data class PrismClientOptions @JvmOverloads constructor(
    val evaluationMode: EvaluationMode = EvaluationMode.LOCAL,
    val configSyncInterval: Duration = Duration.ofSeconds(60),
    val initializationTimeout: Duration = Duration.ofSeconds(5),
    val eventFlushInterval: Duration = Duration.ofSeconds(5),
    val eventBatchSize: Int = 100,
    val eventQueueCapacity: Int = 10_000,
    val exposureCacheMaximumSize: Long = 10_000,
    val shutdownTimeout: Duration = Duration.ofSeconds(5),
    val exposureDedupCapacity: Int = 100_000,
    val apiKey: String? = null,
    val stickyAssignmentStore: StickyAssignmentStore = InMemoryStickyAssignmentStore(),
    val configStreaming: Boolean = false,
    val flushTimeout: Duration = Duration.ofSeconds(5)
) {
    init {
        require(configSyncInterval.toMillis() > 0 && eventFlushInterval.toMillis() > 0)
        require(!initializationTimeout.isNegative && shutdownTimeout.toMillis() > 0)
        require(flushTimeout.toMillis() > 0)
        require(eventBatchSize in 1..1000 && eventQueueCapacity >= eventBatchSize)
        require(exposureCacheMaximumSize > 0)
        require(exposureDedupCapacity > 0)
        require(apiKey == null || (apiKey.length in 32..512 && apiKey.all { it.code in 33..126 && it != ',' })) {
            "API key must contain 32–512 printable non-comma characters"
        }
    }
    override fun toString() = "PrismClientOptions(evaluationMode=$evaluationMode, apiKey=<redacted>)"
}
