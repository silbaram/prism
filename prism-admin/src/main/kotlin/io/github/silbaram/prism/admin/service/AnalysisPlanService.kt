package io.github.silbaram.prism.admin.service

import com.fasterxml.jackson.module.kotlin.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.security.core.context.SecurityContextHolder
import java.time.*
import io.github.silbaram.prism.admin.exception.requireValidInput as require

data class AnalysisPlanInput(val controlVariant: String, val outcomeHours: Int = 24, val latenessHours: Int = 24,
    val segments: Map<String, List<String>> = emptyMap(), val cupedEnabled: Boolean = false, val baselineCutoff: LocalDateTime? = null, val baselineMetric: String? = null)

@Service @Transactional
class AnalysisPlanService(private val population: PopulationService, private val experiments: ExperimentRepository,
                          private val impressions: ImpressionLogRepository, private val plans: AnalysisPlanRepository,
                          private val changes: ExperimentChangeRepository) {
    private val mapper = jacksonObjectMapper()
    fun create(id: Long, input: AnalysisPlanInput): AnalysisPlanEntity {
        population.lock()
        val experiment = experiments.findForUpdate(id) ?: throw io.github.silbaram.prism.admin.exception.ExperimentNotFoundException(id)
        require(experiment.status == ExperimentStatus.DRAFT && !experiment.configurationLocked && !impressions.existsByExperimentKey(experiment.key)) {
            "분석 계획은 최초 시작·예약·노출 이전에만 만들 수 있습니다."
        }
        require(!plans.existsById(id)) { "이미 고정된 분석 계획입니다. 변경하려면 새 실험을 만드세요." }
        val groups = experiment.variants.filter { it.weight > 0 }
        require(groups.size in 2..8 && groups.any { it.name == input.controlVariant }) { "양수 가중치 변형 2–8개와 유효한 대조군이 필요합니다." }
        require(!experiment.goalEventName.isNullOrBlank()) { "목표 이벤트가 필요합니다." }
        require(input.outcomeHours in 1..720 && input.latenessHours in 0..168) { "관측 기간은 1–720시간, 지연 허용은 0–168시간입니다." }
        require(input.segments.size <= 5 && input.segments.all { (key, values) ->
            key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) && values.size in 1..20 && values.distinct().size == values.size &&
                values.all { it.isNotBlank() && it.length <= 64 && it == it.trim() }
        }) { "세그먼트는 최대 5개이며 각각 고유한 값 1–20개를 사전 등록하세요." }
        val now = LocalDateTime.now(ZoneOffset.UTC)
        require(input.cupedEnabled == (input.baselineCutoff != null)) { "CUPED를 사용하면 사전 데이터 마감 시각이 필요합니다." }
        require(if (input.cupedEnabled) !input.baselineMetric.isNullOrBlank() && input.baselineMetric.length <= 255 else input.baselineMetric == null) { "CUPED 사전 지표의 이름과 관측 구간을 설명하세요 (최대 255자)." }
        input.baselineCutoff?.let { require(it <= now && it.toInstant(ZoneOffset.UTC).epochSecond >= 1 && it.nano % 1000 == 0) { "사전 데이터 마감 시각은 1970-01-01T00:00:01 UTC 이후부터 현재까지이며 소수점은 최대 6자리입니다." } }
        val plan = plans.saveAndFlush(AnalysisPlanEntity(id, input.controlVariant, input.outcomeHours, input.latenessHours,
            mapper.writeValueAsString(input.segments), input.cupedEnabled, input.baselineCutoff, now, input.baselineMetric))
        // Lock the statistical design immediately; start/pause and traffic expansion remain available.
        experiment.configurationLocked = true
        changes.save(ExperimentChangeEntity(experimentId = id, experimentKey = experiment.key, action = "ANALYSIS_PLAN",
            beforeSnapshot = null, afterSnapshot = mapper.writeValueAsString(mapOf("controlVariant" to input.controlVariant, "outcomeHours" to input.outcomeHours,
                "latenessHours" to input.latenessHours, "segments" to input.segments, "cupedEnabled" to input.cupedEnabled,
                "baselineCutoff" to input.baselineCutoff?.toString(), "baselineMetric" to input.baselineMetric)),
            actor = SecurityContextHolder.getContext().authentication?.name ?: "system"))
        return plan
    }
}
