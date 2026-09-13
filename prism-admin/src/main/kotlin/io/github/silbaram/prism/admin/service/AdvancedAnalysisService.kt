package io.github.silbaram.prism.admin.service

import com.fasterxml.jackson.module.kotlin.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.*

data class AdvancedGroup(val variant: String, val users: Long, val conversions: Long, val rate: Double?, val baselineUsers: Long)
data class AdvancedComparison(val variant: String, val difference: Double?, val sequential: AnalysisInterval?,
    val bayesian: BayesianEffect?, val cuped: CupedEffect?)
data class SegmentEffect(val key: String, val value: String?, val group: AdvancedGroup, val difference: Double?)
data class AdvancedReport(val plan: AnalysisPlanEntity?, val pendingUsers: Long = 0, val invalidUsers: Long = 0,
    val srm: SrmResult? = null, val message: String = "시작 전 분석 계획을 설정하세요.", val available: Boolean = false,
    val groups: List<AdvancedGroup> = emptyList(), val comparisons: List<AdvancedComparison> = emptyList(),
    val segments: List<SegmentEffect> = emptyList())

@Service @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class AdvancedAnalysisService(private val experiments: ExperimentRepository, private val plans: AnalysisPlanRepository,
                              private val observations: AnalysisObservationRepository, private val impressions: ImpressionLogRepository) {
    private val mapper = jacksonObjectMapper()
    fun report(id: Long): AdvancedReport {
        val experiment = experiments.findById(id).orElseThrow { IllegalArgumentException("실험을 찾을 수 없습니다.") }
        val plan = plans.findById(id).orElse(null) ?: return AdvancedReport(null)
        val variants = experiment.variants.filter { it.weight > 0 }.map { it.name }
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
        val enrolled = observations.enrollment(id).associate { it[0] as String to it[1] as Long }
        val srm = sampleRatioMismatch(experiment.variants.map { it.name to it.weight }, enrolled, impressions.countExposedUsers(experiment.key))
        val invalid = observations.countByExperimentIdAndInvalidReasonIsNotNull(id)
        val pending = observations.countByExperimentIdAndFinalizedAtIsNull(id)
        val available = srm.status == SrmStatus.PASS && invalid == 0L && plan.controlVariant in variants
        fun group(name: String, values: AnalysisMoments) = AdvancedGroup(name, values.n, values.successes, values.rate, values.baselineCount)
        val control = overall[plan.controlVariant]
        val comparisons = variants.filter { it != plan.controlVariant }.map { variant ->
            val treatment = overall.getValue(variant)
            AdvancedComparison(variant, if (control?.rate != null && treatment.rate != null) treatment.rate!! - control.rate!! else null,
                if (available && control != null) sequentialDifference(control, treatment, variants.size) else null,
                if (available && control != null) bayesianEffect(control, treatment) else null,
                if (available && control != null && plan.cupedEnabled) cupedEffect(control, treatment) else null)
        }
        val segments = segmented.flatMap { (segment, groups) ->
            val reference = groups[plan.controlVariant]?.rate
            groups.map { (variant, stats) -> SegmentEffect(segment.first, segment.second, group(variant, stats),
                if (reference != null && stats.rate != null) stats.rate!! - reference else null) }
        }
        return AdvancedReport(plan, pending, invalid, srm,
            if (invalid > 0) "늦은 이벤트 또는 변형 중복 등 무효 데이터가 있어 추론을 차단했습니다."
            else if (srm.status != SrmStatus.PASS) srm.message
            else "고정된 관측 기간을 마친 사용자만 분석합니다. 기존 전체 기간 CVR과 분모가 다릅니다.",
            available, overall.map { group(it.key, it.value) }, comparisons, segments)
    }
}
