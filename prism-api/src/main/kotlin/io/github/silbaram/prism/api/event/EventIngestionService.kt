package io.github.silbaram.prism.api.event

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.common.rest.dto.event.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import jakarta.persistence.EntityManager
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class EventIngestionService(
    transactionManager: PlatformTransactionManager,
    private val entityManager: EntityManager,
    private val receipts: EventReceiptRepository,
    private val impressions: ImpressionLogRepository,
    private val conversions: ConversionLogRepository,
    private val experiments: ExperimentRepository
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val mapper = jacksonObjectMapper()

    fun ingest(events: List<ClientEvent>): EventsResponse {
        // Same-batch conversions can precede exposures on the wire.
        val results = events.sortedBy { if (it.type == "exposure") 0 else 1 }
            .associate { it.eventId to ingestOne(it) }
        return EventsResponse(events.map { results.getValue(it.eventId) })
    }

    private fun ingestOne(event: ClientEvent): EventResult {
        try { validate(event) } catch (e: IllegalArgumentException) {
            return EventResult(event.eventId, EventStatus.REJECTED, e.message)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(event))
            .joinToString("") { "%02x".format(it) }
        return try {
            requireNotNull(transaction.execute {
                val previous = receipts.findById(event.eventId).orElse(null)
                if (previous != null) return@execute duplicate(event, digest, previous)
                // persist (not merge) reserves the global ID. A racing insert rolls this transaction back.
                entityManager.persist(EventReceiptEntity(event.eventId, digest, event.configVersion))
                entityManager.flush()
                val time = LocalDateTime.ofInstant(Instant.parse(event.timestamp), ZoneOffset.UTC)
                if (event.type == "exposure") {
                    require(experiments.findByKey(event.experimentKey) != null) { "Unknown experiment" }
                    // Buffered events may use a previous config: never reassign against today's weights/status.
                    impressions.saveAndFlush(ImpressionLogEntity(experimentKey = event.experimentKey,
                        variant = event.variant, userId = event.userId, timestamp = time, eventId = event.eventId))
                } else {
                    val exposureId = requireNotNull(event.exposureEventId)
                    val exposure = impressions.findByEventId(exposureId) ?: run {
                        require(!receipts.existsById(exposureId)) { "Referenced event is not an available exposure" }
                        throw MissingExposure()
                    }
                    require(exposure.userId == event.userId && exposure.experimentKey == event.experimentKey &&
                        exposure.variant == event.variant) { "Exposure identity or variant mismatch" }
                    require(receipts.findById(requireNotNull(event.exposureEventId)).orElse(null)?.configVersion == event.configVersion) {
                        "Exposure configuration mismatch"
                    }
                    conversions.saveAndFlush(ConversionLogEntity(experimentKey = event.experimentKey,
                        variant = event.variant, userId = event.userId, eventName = requireNotNull(event.eventName),
                        impressionId = exposure.id, timestamp = time, eventId = event.eventId))
                }
                EventResult(event.eventId, EventStatus.ACCEPTED)
            })
        } catch (_: MissingExposure) {
            EventResult(event.eventId, EventStatus.RETRY, "Referenced exposure has not arrived")
        } catch (e: IllegalArgumentException) {
            EventResult(event.eventId, EventStatus.REJECTED, e.message)
        } catch (e: RuntimeException) {
            if (e !is DataAccessException && e !is jakarta.persistence.PersistenceException &&
                e !is org.springframework.transaction.TransactionException) throw e
            // A concurrent retry may already have committed. Read only after the failed transaction ended.
            val previous = try { receipts.findById(event.eventId).orElse(null) } catch (_: DataAccessException) { null }
            if (previous == null) EventResult(event.eventId, EventStatus.RETRY, "Storage temporarily unavailable")
            else duplicate(event, digest, previous)
        }
    }

    private fun duplicate(event: ClientEvent, hash: String, previous: EventReceiptEntity): EventResult =
        if (hash == previous.payloadHash) EventResult(event.eventId, EventStatus.DUPLICATE)
        else EventResult(event.eventId, EventStatus.REJECTED, "Event ID already used with a different payload")

    private fun validate(event: ClientEvent) {
        fun uuid(value: String) = UUID.fromString(value).toString() == value
        require(uuid(event.eventId)) { "eventId must be a canonical UUID" }
        require(event.type in setOf("exposure", "conversion")) { "Unknown event type" }
        require(listOf(event.userId, event.experimentKey, event.variant).all { it.isNotBlank() && it.length <= 255 }) {
            "Identity fields must contain 1-255 characters"
        }
        require(event.configVersion.matches(Regex("[0-9a-f]{64}"))) { "Invalid configVersion" }
        val time = try { Instant.parse(event.timestamp) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid UTC timestamp")
        }
        require(time >= Instant.ofEpochSecond(1) && time <= Instant.ofEpochSecond(Int.MAX_VALUE.toLong())) {
            "Timestamp outside MySQL TIMESTAMP range"
        }
        if (event.type == "conversion") {
            val name = event.eventName
            val exposureId = event.exposureEventId
            require(!name.isNullOrBlank() && name.length <= 255) { "Invalid eventName" }
            require(exposureId != null && uuid(exposureId) && exposureId != event.eventId) {
                "Conversion must reference a different exposureEventId"
            }
        } else require(event.eventName == null && event.exposureEventId == null) { "Invalid exposure fields" }
    }

    private class MissingExposure : RuntimeException()
}
