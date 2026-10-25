package io.github.silbaram.prism.common.rest.dto.diagnostics

import java.time.Instant

/** Aggregate DB state only; QUEUED admission and materialization are intentionally distinct. */
data class PipelineDiagnostics(
    val observedAt: Instant,
    val mode: String,
    val consumerEnabled: Boolean,
    val inbox: InboxDiagnostics,
    val outbox: OutboxDiagnostics
)

data class InboxDiagnostics(
    val pendingCount: Long,
    val dueCount: Long,
    val attemptedPendingCount: Long,
    val completedCount: Long,
    val rejectedCount: Long,
    val oldestPendingAgeSeconds: Long?
)

data class OutboxDiagnostics(
    val pendingCount: Long,
    val warehousePendingCount: Long,
    val deadLetterPendingCount: Long,
    val oldestKnownPendingAgeSeconds: Long?,
    val unknownAgeCount: Long
)
