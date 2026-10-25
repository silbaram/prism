package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.core.JsonProcessingException
import io.github.silbaram.prism.common.rest.dto.event.EventStatus
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletionException

internal class DiagnosticException(val failure: SdkDiagnosticFailure, val httpStatus: Int? = null) : RuntimeException()

private fun diagnosticCause(error: Exception): Throwable {
    var cause: Throwable = error
    var depth = 0
    while ((cause is ExecutionException || cause is CompletionException) && depth++ < 8) {
        val next = cause.cause ?: break
        if (next === cause) break
        cause = next
    }
    return cause
}

internal fun diagnosticFailure(error: Exception): SdkDiagnosticFailure = when (val cause = diagnosticCause(error)) {
    is DiagnosticException -> cause.failure
    is InterruptedException -> SdkDiagnosticFailure.INTERRUPTED
    is HttpTimeoutException, is TimeoutException -> SdkDiagnosticFailure.TIMEOUT
    is JsonProcessingException, is IllegalArgumentException -> SdkDiagnosticFailure.INVALID_RESPONSE
    else -> SdkDiagnosticFailure.TRANSPORT_ERROR
}

internal fun diagnosticHttpStatus(error: Exception): Int? = (diagnosticCause(error) as? DiagnosticException)?.httpStatus

/** All calls are guarded by LocalEvaluationClient's queue monitor. Admission times are bounded by that queue. */
internal class EventDeliveryMonitor(private val clock: Clock, private val ticker: () -> Long) {
    private val admittedAt = LinkedHashMap<String, Long>()
    private var enqueued = 0L
    var capacityRejected = 0L
    private var accepted = 0L
    private var duplicate = 0L
    private var queued = 0L
    private var retry = 0L
    private var rejected = 0L
    private var discarded = 0L
    private var acknowledgedBatches = 0L
    private var failedBatches = 0L
    private var attemptAt: Instant? = null
    private var responseAt: Instant? = null
    private var status = BatchDeliveryStatus.NOT_ATTEMPTED
    private var failureAt: Instant? = null
    private var failure: SdkDiagnosticFailure? = null
    private var failureHttpStatus: Int? = null

    fun enqueued(id: String) { admittedAt[id] = ticker(); enqueued++ }
    fun beginBatch() { attemptAt = clock.instant(); status = BatchDeliveryStatus.IN_FLIGHT }
    fun acknowledgedBatch() { acknowledgedBatches++; responseAt = clock.instant(); status = BatchDeliveryStatus.ACKNOWLEDGED }
    fun failedBatch(error: Exception) {
        failedBatches++; failureAt = clock.instant(); failure = diagnosticFailure(error)
        failureHttpStatus = diagnosticHttpStatus(error); status = BatchDeliveryStatus.FAILED
    }
    fun acknowledged(id: String, result: EventStatus) {
        when (result) {
            EventStatus.ACCEPTED -> accepted++
            EventStatus.DUPLICATE -> duplicate++
            EventStatus.QUEUED -> queued++
            EventStatus.RETRY -> retry++
            EventStatus.REJECTED -> rejected++
        }
        if (result != EventStatus.RETRY) admittedAt.remove(id)
    }
    fun discarded(id: String) { if (admittedAt.remove(id) != null) discarded++ }

    fun snapshot(pending: Int, capacity: Int): EventDeliveryDiagnostics = EventDeliveryDiagnostics(
        pending, capacity, admittedAt.values.firstOrNull()?.let { Duration.ofNanos((ticker() - it).coerceAtLeast(0)) },
        enqueued, capacityRejected, accepted, duplicate, queued, retry, rejected, discarded,
        acknowledgedBatches, failedBatches, attemptAt, responseAt, status, failureAt, failure, failureHttpStatus
    )
}
