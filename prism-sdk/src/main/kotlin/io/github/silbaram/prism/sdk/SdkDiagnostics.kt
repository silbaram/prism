package io.github.silbaram.prism.sdk

import java.time.Duration
import java.time.Instant

/** KEEP_LAST preserves the existing fallback; BLOCK_NEW_ASSIGNMENTS requires maxConfigAge. */
enum class StaleConfigPolicy { KEEP_LAST, BLOCK_NEW_ASSIGNMENTS }
enum class ConfigurationStatus { NOT_INITIALIZED, UNBOUNDED, FRESH, STALE }
enum class ConfigSyncResult { NOT_ATTEMPTED, SUCCESS, FAILED }
enum class BatchDeliveryStatus { NOT_ATTEMPTED, IN_FLIGHT, ACKNOWLEDGED, FAILED }

/** Stable categories only: no exception messages, credentials, rules or user attributes. */
enum class SdkDiagnosticFailure { HTTP_ERROR, INVALID_RESPONSE, OUTDATED_REVISION, TIMEOUT, INTERRUPTED, TRANSPORT_ERROR }

/** Immutable, in-process observation. LOCAL components are null in REMOTE mode, not healthy zeros. */
data class SdkDiagnostics(
    val evaluationMode: EvaluationMode,
    val closed: Boolean,
    val configuration: ConfigurationDiagnostics?,
    val events: EventDeliveryDiagnostics?,
    val exposureDedup: ExposureDedupSnapshot?
)

data class ConfigurationDiagnostics(
    val status: ConfigurationStatus,
    val version: String?,
    val revision: Long?,
    val age: Duration?,
    val maxAge: Duration?,
    val stalePolicy: StaleConfigPolicy,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastSyncResult: ConfigSyncResult,
    val consecutiveFailureCount: Long,
    val lastFailureAt: Instant?,
    val lastFailure: SdkDiagnosticFailure?,
    val lastFailureHttpStatus: Int?,
    val targetingRejectedExperimentCount: Int,
    /** Rejected calls (including repeated calls), not unique users. */
    val staleRejectedCount: Long
)

data class EventDeliveryDiagnostics(
    val pendingCount: Int,
    val capacity: Int,
    val oldestPendingAge: Duration?,
    val enqueuedCount: Long,
    val capacityRejectedCount: Long,
    val acceptedCount: Long,
    val duplicateCount: Long,
    /** Kafka admission; does not claim DB materialization. */
    val durablyQueuedCount: Long,
    /** RETRY responses, including repeated responses for the same event ID. */
    val retryResponseCount: Long,
    val rejectedCount: Long,
    val discardedDependentCount: Long,
    val acknowledgedBatchCount: Long,
    val failedBatchCount: Long,
    val lastBatchAttemptAt: Instant?,
    val lastBatchResponseAt: Instant?,
    /** ACKNOWLEDGED means a complete valid ACK, which may contain RETRY or REJECTED. */
    val lastBatchStatus: BatchDeliveryStatus,
    val lastFailureAt: Instant?,
    val lastFailure: SdkDiagnosticFailure?,
    val lastFailureHttpStatus: Int?
)

data class ExposureDedupSnapshot(
    val experimentCount: Int,
    val populationCount: Int,
    val capacityPerScope: Int,
    val experimentRejectedCount: Long,
    val populationRejectedCount: Long
)
