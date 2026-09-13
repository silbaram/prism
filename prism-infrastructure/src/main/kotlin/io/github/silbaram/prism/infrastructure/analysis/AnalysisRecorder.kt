package io.github.silbaram.prism.infrastructure.analysis

import com.fasterxml.jackson.module.kotlin.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime

/** Runs in the event transaction: metadata and exposure must commit or roll back together. */
@Service @Transactional(propagation = Propagation.MANDATORY)
class AnalysisRecorder(private val plans: AnalysisPlanRepository, private val observations: AnalysisObservationRepository,
                       private val experiments: ExperimentRepository, private val entityManager: EntityManager) {
    private val mapper = jacksonObjectMapper()
    private fun identity(experimentId: Long, userId: String) = MessageDigest.getInstance("SHA-256")
        .digest("$experimentId:$userId".toByteArray()).joinToString("") { "%02x".format(it) }

    fun exposure(exposure: ImpressionLogEntity, segments: Map<String, String> = emptyMap(), baseline: Double? = null,
                 baselineMeasuredAt: LocalDateTime? = null) {
        val experiment = experiments.findByKey(exposure.experimentKey) ?: return
        val plan = plans.findById(experiment.id!!).orElse(null) ?: return
        // JDBC/database precision can differ from the nanosecond input still held by JPA.
        // Compare exactly the timestamp used by persisted outcome queries (DIRECT and REMOTE).
        entityManager.refresh(exposure)
        val id = identity(plan.experimentId, exposure.userId)
        val existing = observations.lock(id)
        if (existing != null && existing.variant != exposure.variant) { existing.invalidReason = "MULTIPLE_VARIANTS"; return }
        if (existing != null && exposure.timestamp >= existing.exposedAt) return
        if (existing?.finalizedAt != null) { existing.invalidReason = "LATE_EARLIER_EXPOSURE"; return }
        val allowed = mapper.readValue<Map<String, List<String>>>(plan.segmentsJson)
        val selected = segments.filter { (key, value) -> value in allowed[key].orEmpty() }
        val baselineValue = baseline?.takeIf { baselineMeasuredAt != null && plan.baselineCutoff != null &&
            baselineMeasuredAt <= plan.baselineCutoff && baselineMeasuredAt < exposure.timestamp }
        val end = exposure.timestamp.plusHours(plan.outcomeHours.toLong())
        require(end.plusHours(plan.latenessHours.toLong()).toInstant(java.time.ZoneOffset.UTC).epochSecond <= Int.MAX_VALUE) { "Analysis observation window exceeds MySQL timestamp range" }
        val row = existing ?: AnalysisObservationEntity(id, plan.experimentId, exposure.userId, exposure.variant,
            exposure.id!!, exposure.timestamp, end, end.plusHours(plan.latenessHours.toLong()))
        row.exposureId = exposure.id!!
        row.exposedAt = exposure.timestamp
        row.outcomeEndsAt = end
        row.maturesAt = end.plusHours(plan.latenessHours.toLong())
        row.segmentsJson = mapper.writeValueAsString(selected)
        row.baselineValue = baselineValue
        if (exposure.timestamp < plan.createdAt) row.invalidReason = "EXPOSURE_BEFORE_PLAN"
        if (existing == null) entityManager.persist(row)
    }

    fun conversion(conversion: ConversionLogEntity) {
        val experiment = experiments.findByKey(conversion.experimentKey) ?: return
        if (conversion.eventName != experiment.goalEventName) return
        val observation = observations.lock(identity(experiment.id!!, conversion.userId)) ?: return
        if (observation.finalizedAt != null && observation.converted == false && conversion.variant == observation.variant) {
            entityManager.refresh(conversion)
            if (conversion.timestamp >= observation.exposedAt && conversion.timestamp < observation.outcomeEndsAt) {
                observation.invalidReason = "LATE_CONVERSION_AFTER_FINALIZATION"
            }
        }
    }
}
