package io.github.silbaram.prism.admin.service

import com.fasterxml.jackson.module.kotlin.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.*
import java.time.LocalDateTime

data class AnalysisRevision(val experimentId: Long, val planCreatedAt: LocalDateTime?,
    val enrolled: Map<String, Long> = emptyMap(), val uniqueUsers: Long = 0, val finalized: Long = 0,
    val pending: Long = 0, val invalid: Long = 0)
data class AnalysisState(val revision: AnalysisRevision, val plan: AnalysisPlanEntity?, val weights: List<Pair<String, Int>>)
data class AnalysisSnapshot(val state: AnalysisState, val overall: Map<String, AnalysisMoments> = emptyMap(),
    val segmented: Map<Pair<String, String?>, Map<String, AnalysisMoments>> = emptyMap())

/** Returns detached, bounded aggregates. Posterior sampling/rendering runs after this transaction ends. */
@Service @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class AnalysisSnapshotReader(private val experiments: ExperimentRepository, private val plans: AnalysisPlanRepository,
                             private val observations: AnalysisObservationRepository, private val impressions: ImpressionLogRepository) {
    private val mapper = jacksonObjectMapper()
    fun revision(id: Long) = state(id).revision

    private fun state(id: Long): AnalysisState {
        val experiment = experiments.findById(id).orElseThrow { IllegalArgumentException("실험을 찾을 수 없습니다.") }
        val plan = plans.findById(id).orElse(null)
        val weights = experiment.variants.map { it.name to it.weight }
        if (plan == null) return AnalysisState(AnalysisRevision(id, null), null, weights)
        val enrolled = observations.enrollment(id).associate { it[0] as String to it[1] as Long }
        return AnalysisState(AnalysisRevision(id, plan.createdAt, enrolled, impressions.countExposedUsers(experiment.key),
            observations.countByExperimentIdAndFinalizedAtIsNotNull(id),
            observations.countByExperimentIdAndFinalizedAtIsNull(id),
            observations.countByExperimentIdAndInvalidReasonIsNotNull(id)), plan, weights)
    }

    fun load(id: Long): AnalysisSnapshot {
        val state = state(id)
        val plan = state.plan ?: return AnalysisSnapshot(state)
        val variants = state.weights.filter { it.second > 0 }.map { it.first }
        val definitions = mapper.readValue<Map<String, List<String>>>(plan.segmentsJson)
        val overall = variants.associateWith { AnalysisMoments() }
        val segmented = definitions.flatMap { (key, values) -> (values + listOf<String?>(null)).map { key to it } }
            .associateWith { variants.associateWith { AnalysisMoments() } }
        var after = ""
        while (true) {
            val page = observations.samples(id, after, PageRequest.of(0, 1000))
            if (page.isEmpty()) break
            page.forEach { row ->
                overall[row.variant]?.add(row.converted, row.baselineValue)
                val values = mapper.readValue<Map<String, String>>(row.segmentsJson)
                definitions.keys.forEach { key -> segmented[key to values[key]]?.get(row.variant)?.add(row.converted, row.baselineValue) }
            }
            after = page.last().id
        }
        return AnalysisSnapshot(state, overall, segmented)
    }
}
