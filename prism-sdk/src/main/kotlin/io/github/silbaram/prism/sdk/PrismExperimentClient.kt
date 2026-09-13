package io.github.silbaram.prism.sdk

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Explicit assignments and exposure-aware tracking, independent of threads and AOP ordering. */
class PrismExperimentClient @JvmOverloads constructor(
    private val prismClient: PrismClient,
    private val assignmentCacheTtl: Duration = Duration.ofSeconds(30),
    assignmentCacheMaximumSize: Long = 10_000
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private data class AssignmentKey(val userId: String, val experimentKey: String)
    private val assignments = Caffeine.newBuilder()
        .expireAfterWrite(assignmentCacheTtl)
        .maximumSize(assignmentCacheMaximumSize)
        .build<AssignmentKey, AssignmentOutcome?>()
    private val lookups = ConcurrentHashMap<AssignmentKey, CompletableFuture<AssignmentOutcome?>>()

    /** Every explicit assign enqueues/persists an exposure, depending on the client's evaluation mode. */
    @JvmOverloads
    fun assign(userId: String, experimentKey: String, attributes: Map<String, Any> = emptyMap()): AssignmentOutcome {
        val key = AssignmentKey(userId, experimentKey)
        val outcome = safely(userId, experimentKey) {
            if (attributes.isEmpty()) prismClient.assign(userId, experimentKey)
            else prismClient.assign(userId, experimentKey, attributes)
        }
        if (outcome.assigned) {
            assignments.put(key, outcome)
            lookups[key]?.complete(outcome)
        } else assignments.invalidate(key)
        return outcome
    }

    /** Checks prior exposure, then tracks the event. A cache miss looks up local exposure or the remote server.
     * Failed lookups are not cached, and tracking never manufactures a new exposure.
     */
    fun trackIfAssigned(userId: String, experimentKey: String, eventName: String): Boolean {
        val key = AssignmentKey(userId, experimentKey)
        val outcome = assignments.getIfPresent(key) ?: lookup(key) ?: return false
        return trackSafely(outcome) {
            prismClient.trackConversion(userId, experimentKey, eventName, assignmentCacheTtl)
        }
    }

    private fun lookup(key: AssignmentKey): AssignmentOutcome? {
        val pending = CompletableFuture<AssignmentOutcome?>()
        val existing = lookups.putIfAbsent(key, pending)
        if (existing != null) return awaitLookup(existing)
        try {
            val outcome = assignments.getIfPresent(key) ?: safely(key.userId, key.experimentKey) {
                prismClient.getAssignment(key.userId, key.experimentKey, assignmentCacheTtl)
            }.takeIf { it.assigned }
            // A local assign can populate the cache while the read-only HTTP lookup is running.
            val current = if (outcome == null) assignments.getIfPresent(key)
                else assignments.asMap().putIfAbsent(key, outcome) ?: outcome
            pending.complete(current)
            return awaitLookup(pending)
        } catch (exception: Throwable) {
            pending.completeExceptionally(exception)
            throw exception
        } finally {
            lookups.remove(key, pending)
        }
    }

    private fun awaitLookup(pending: CompletableFuture<AssignmentOutcome?>): AssignmentOutcome? = try {
        pending.get()
    } catch (exception: Exception) {
        if (exception is InterruptedException) Thread.currentThread().interrupt()
        null
    }

    /** Local mode preserves this outcome's exposure ID, including after reassignments or across instances. */
    fun track(outcome: AssignmentOutcome, eventName: String): Boolean = trackSafely(outcome) {
        prismClient.trackConversion(AssignmentResponse(outcome.userId, outcome.experimentKey, outcome.variant,
            outcome.resultCode, outcome.resultMessage, outcome.configVersion, outcome.exposureEventId), eventName)
    }

    private fun trackSafely(outcome: AssignmentOutcome, send: () -> Boolean): Boolean {
        if (!outcome.assigned || outcome.variant.isNullOrBlank() || outcome.resultCode != ResponseCode.SUCCESS.code) return false
        val accepted = try {
            send()
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Conversion failed: userId={}, experimentKey={}, error={}",
                maskUserId(outcome.userId), outcome.experimentKey, exception.javaClass.simpleName)
            false
        }
        if (!accepted) assignments.invalidate(AssignmentKey(outcome.userId, outcome.experimentKey))
        return accepted
    }

    private fun safely(userId: String, experimentKey: String, request: () -> AssignmentResponse): AssignmentOutcome =
        try {
            val response = request()
            if (response.userId != userId || response.experimentKey != experimentKey) {
                AssignmentOutcome.failed(userId, experimentKey, "Mismatched assignment response")
            } else AssignmentOutcome.from(response)
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Assignment lookup failed: userId={}, experimentKey={}, error={}",
                maskUserId(userId), experimentKey, exception.javaClass.simpleName)
            AssignmentOutcome.failed(userId, experimentKey, exception.javaClass.simpleName)
        }
}

/**
 * 실험 할당 결과를 담는 데이터 클래스입니다.
 *
 * @property userId 사용자 ID
 * @property experimentKey 실험 키
 * @property variant 할당된 variant (실패 시 null)
 * @property assigned 할당 성공 여부 (true: 성공, false: 실패)
 * @property resultCode 결과 코드 ("0000": 성공, 그 외: 실패 사유)
 * @property resultMessage 결과 메시지
 */
data class AssignmentOutcome @JvmOverloads constructor(
    val userId: String,
    val experimentKey: String,
    val variant: String?,
    val assigned: Boolean,
    val resultCode: String,
    val resultMessage: String,
    val configVersion: String? = null,
    val exposureEventId: String? = null
) {
    companion object {
        /**
         * API 응답으로부터 AssignmentOutcome을 생성합니다.
         * variant가 있고 resultCode가 성공이면 assigned=true로 설정됩니다.
         */
        fun from(response: AssignmentResponse): AssignmentOutcome {
            val assigned = !response.variant.isNullOrBlank() && response.resultCode == ResponseCode.SUCCESS.code
            return AssignmentOutcome(
                userId = response.userId,
                experimentKey = response.experimentKey,
                variant = response.variant,
                assigned = assigned,
                resultCode = response.resultCode,
                resultMessage = response.resultMessage,
                configVersion = response.configVersion,
                exposureEventId = response.exposureEventId
            )
        }

        /**
         * 실패한 AssignmentOutcome을 생성합니다.
         * 네트워크 오류나 예외 발생 시 사용됩니다.
         */
        fun failed(userId: String, experimentKey: String, message: String): AssignmentOutcome {
            return AssignmentOutcome(
                userId = userId,
                experimentKey = experimentKey,
                variant = null,
                assigned = false,
                resultCode = SdkResponseCode.CLIENT_ERROR.code,
                resultMessage = message
            )
        }
    }
}
