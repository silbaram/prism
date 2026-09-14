package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import org.springframework.stereotype.Service

data class AdvancedGroup(val variant: String, val users: Long, val conversions: Long, val rate: Double?, val baselineUsers: Long)
data class AdvancedComparison(val variant: String, val difference: Double?, val sequential: AnalysisInterval?,
    val bayesian: BayesianEffect?, val cuped: CupedEffect?)
data class SegmentEffect(val key: String, val value: String?, val group: AdvancedGroup, val difference: Double?)
data class AdvancedReport(val plan: AnalysisPlanEntity?, val pendingUsers: Long = 0, val invalidUsers: Long = 0,
    val srm: SrmResult? = null, val message: String = "시작 전 분석 계획을 설정하세요.", val available: Boolean = false,
    val groups: List<AdvancedGroup> = emptyList(), val comparisons: List<AdvancedComparison> = emptyList(),
    val segments: List<SegmentEffect> = emptyList())

@Service
class AdvancedAnalysisService(private val reader: AnalysisSnapshotReader) {
    private data class Cached(val revision: AnalysisRevision, val report: AdvancedReport)
    private val cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
        .maximumSize(64).expireAfterAccess(java.time.Duration.ofMinutes(10)).build<Long, Cached>()
    private val locks = Array(64) { Any() }

    fun report(id: Long): AdvancedReport = synchronized(locks[Math.floorMod(id.hashCode(), locks.size)]) {
        // Wait for concurrent report generation before borrowing a database connection.
        val revision = reader.revision(id)
        cache.getIfPresent(id)?.takeIf { it.revision == revision }?.let { return@synchronized it.report }
        val snapshot = reader.load(id)
        val report = calculate(snapshot)
        cache.put(id, Cached(snapshot.state.revision, report))
        report
    }

    private fun calculate(snapshot: AnalysisSnapshot): AdvancedReport {
        val plan = snapshot.state.plan ?: return AdvancedReport(null)
        val revision = snapshot.state.revision
        val overall = snapshot.overall
        val segmented = snapshot.segmented
        val variants = snapshot.state.weights.filter { it.second > 0 }.map { it.first }
        val srm = sampleRatioMismatch(snapshot.state.weights, revision.enrolled, revision.uniqueUsers)
        val invalid = revision.invalid
        val pending = revision.pending
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
