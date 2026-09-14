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
    private val experiments: ExperimentRepository,
    private val outbox: PipelineOutboxRepository,
    private val pipeline: io.github.silbaram.prism.api.pipeline.PipelineProperties,
    private val populationExposures: PopulationExposureRepository,
    private val populationConversions: PopulationConversionRepository,
    private val policies: PopulationPolicyRepository,
    private val analysis: io.github.silbaram.prism.infrastructure.analysis.AnalysisRecorder
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val mapper = jacksonObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun ingest(events: List<ClientEvent>): EventsResponse {
        // Same-batch conversions can precede exposures on the wire.
        val results = events.sortedBy { if (it.type.endsWith("exposure")) 0 else 1 }
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
                if (event.type.startsWith("population_")) {
                    recordPopulation(event, time)
                } else if (event.type == "exposure") {
                    val experiment = requireNotNull(experiments.findByKey(event.experimentKey)) { "Unknown experiment" }
                    // Buffered events may use a previous config: never reassign against today's weights/status.
                    val recorded = impressions.saveAndFlush(ImpressionLogEntity(experimentKey = event.experimentKey,
                        variant = event.variant, userId = event.userId, timestamp = time, eventId = event.eventId))
                    analysis.exposure(recorded, event.analysis?.segments.orEmpty(), event.analysis?.baselineValue,
                        event.analysis?.baselineMeasuredAt?.let { LocalDateTime.ofInstant(Instant.parse(it), ZoneOffset.UTC) }, experiment)
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
                    val recorded = conversions.saveAndFlush(ConversionLogEntity(experimentKey = event.experimentKey,
                        variant = event.variant, userId = event.userId, eventName = requireNotNull(event.eventName),
                        impressionId = exposure.id, timestamp = time, eventId = event.eventId))
                    analysis.conversion(recorded)
                }
                if (pipeline.mode == io.github.silbaram.prism.api.pipeline.PipelineMode.KAFKA) entityManager.persist(PipelineOutboxEntity(
                    event.eventId, "WAREHOUSE", mapper.writeValueAsString(event)))
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

    fun validate(event: ClientEvent) {
        if (event.extensions.isNotEmpty()) {
            val reserved = setOf("eventId", "type", "userId", "experimentKey", "variant", "timestamp", "configVersion", "eventName", "exposureEventId", "analysis")
            require(event.extensions.size <= 16 && event.extensions.keys.all { it.isNotBlank() && it.length <= 64 && it !in reserved } &&
                mapper.writeValueAsBytes(event.extensions).size <= 16384) { "Invalid or oversized event extension metadata" }
        }
        fun uuid(value: String) = UUID.fromString(value).toString() == value
        require(uuid(event.eventId)) { "eventId must be a canonical UUID" }
        require(event.type in setOf("exposure", "conversion", "population_exposure", "population_conversion")) { "Unknown event type" }
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
        event.analysis?.let { require(event.type == "exposure") { "Analysis context is only allowed on experiment exposures" }; it.validate() }
        if (event.type.endsWith("conversion")) {
            val name = event.eventName
            val exposureId = event.exposureEventId
            require(!name.isNullOrBlank() && name.length <= 255) { "Invalid eventName" }
            require(exposureId != null && uuid(exposureId) && exposureId != event.eventId) {
                "Conversion must reference a different exposureEventId"
            }
        } else require(event.eventName == null && event.exposureEventId == null) { "Invalid exposure fields" }
    }

    private class MissingExposure : RuntimeException()

    private fun recordPopulation(event: ClientEvent, time: LocalDateTime) {
        val policy = policies.findById(1).orElseThrow()
        require(policy.holdoutBasisPoints != null && policy.holdoutKey == event.experimentKey) { "Unknown population cohort" }
        val cohort = if (policy.toDomain().excludes(event.userId)) "HOLDOUT" else "ELIGIBLE"
        require(event.variant == cohort) { "Population cohort mismatch" }
        if (event.type == "population_exposure") {
            entityManager.persist(PopulationExposureEntity(event.eventId, policy.holdoutKey, event.userId, cohort, time))
        } else {
            val exposureId = requireNotNull(event.exposureEventId)
            val exposure = populationExposures.findById(exposureId).orElse(null) ?: run {
                require(!receipts.existsById(exposureId)) { "Referenced event is not a population exposure" }
                throw MissingExposure()
            }
            require(exposure.cohortKey == policy.holdoutKey && exposure.userId == event.userId && exposure.variant == cohort &&
                receipts.findById(exposureId).orElseThrow().configVersion == event.configVersion) { "Population exposure mismatch" }
            entityManager.persist(PopulationConversionEntity(event.eventId, policy.holdoutKey, event.userId, cohort,
                requireNotNull(event.eventName), exposureId, time))
        }
    }
}
