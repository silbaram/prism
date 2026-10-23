package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.core.splitter.TrafficSplitter
import io.github.silbaram.prism.core.targeting.TargetingRule
import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.PopulationPolicyRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.layerAllocation
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset

enum class PreviewMode { CURRENT, DRAFT }

@Service
@Transactional(readOnly = true)
class SimulatorService(
    private val experimentRepository: ExperimentRepository,
    private val policies: PopulationPolicyRepository,
    private val targeting: TargetingPreviewService
) {

    fun simulateAssignment(experimentId: Long, userId: String, attributes: String = "{}", mode: PreviewMode = PreviewMode.CURRENT): SimulationResult {
        require(userId.isNotBlank() && userId.length <= 255) { "User ID는 1–255자로 입력하세요." }
        val experimentEntity = experimentRepository.findById(experimentId)
            .orElseThrow { ExperimentNotFoundException(experimentId) }

        val context = targeting.attributes(attributes)
        val rules = experimentEntity.targetingRules.map { it.expression }
        val target = targeting.evaluate(rules, context)
        val experiment = Experiment(
            key = experimentEntity.key,
            variants = experimentEntity.variants.map { Variant(it.name, it.weight) },
            targetingRules = rules.map(::TargetingRule),
            trafficAllocation = experimentEntity.trafficAllocation,
            startsAt = experimentEntity.startsAt?.toInstant(java.time.ZoneOffset.UTC),
            endsAt = experimentEntity.endsAt?.toInstant(java.time.ZoneOffset.UTC),
            layer = experimentEntity.layerAllocation(), holdout = policies.findById(1).orElseThrow().toDomain()
        )

        val now = Instant.now()
        val reason = when {
            mode == PreviewMode.CURRENT && experimentEntity.status != ExperimentStatus.ACTIVE -> "실험 상태가 ACTIVE가 아닙니다 (${experimentEntity.status})."
            experiment.startsAt?.let { now < it } == true -> "실험 시작 시각 전입니다."
            experiment.endsAt?.let { now >= it } == true -> "실험 종료 시각 이후입니다."
            experiment.holdout.excludes(userId) -> "전역 홀드아웃에 해당합니다."
            experiment.layer?.includes(userId) == false -> "레이어 참여 범위 밖입니다."
            !target.matched -> "타기팅 조건에 일치하지 않습니다."
            else -> null
        }
        val assignedVariant = if (reason == null) TrafficSplitter.assign(experiment, userId, context, now) else null

        return SimulationResult(
            experimentKey = experimentEntity.key,
            userId = userId,
            assignedVariant = assignedVariant?.name,
            targetingMatched = target.matched, ruleMatches = target.rules,
            reason = reason ?: if (assignedVariant == null) "실험 참여 비율 범위 밖입니다." else "참여 조건을 만족합니다.",
            mode = mode, stickyBucketing = experimentEntity.stickyBucketing
        )
    }

}

data class SimulationResult(
    val experimentKey: String,
    val userId: String,
    val assignedVariant: String?,
    val targetingMatched: Boolean,
    val ruleMatches: List<Boolean>,
    val reason: String,
    val mode: PreviewMode,
    val stickyBucketing: Boolean
)
