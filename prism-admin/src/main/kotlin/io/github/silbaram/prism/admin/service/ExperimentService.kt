package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.admin.exception.DuplicateExperimentKeyException
import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import io.github.silbaram.prism.admin.exception.InvalidVariantWeightException
import io.github.silbaram.prism.core.model.InvalidVariantWeightsException
import io.github.silbaram.prism.core.model.validateVariantWeights
import io.github.silbaram.prism.core.model.validateExperimentIdentities
import io.github.silbaram.prism.admin.service.dto.ExperimentCreateDto
import io.github.silbaram.prism.admin.service.dto.ExperimentUpdateDto
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.TargetingRuleEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.VariantEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentChangeEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentChangeRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ImpressionLogRepository

@Service
@Transactional
class ExperimentService(
    private val experimentRepository: ExperimentRepository,
    private val changeRepository: ExperimentChangeRepository,
    private val impressionRepository: ImpressionLogRepository,
    private val population: PopulationService
) {
    private val mapper = jacksonObjectMapper()

    fun createExperiment(dto: ExperimentCreateDto): ExperimentEntity {
        population.lock()
        population.validateLayer(null, dto.layerKey, dto.layerStart, dto.layerEnd)
        validateExperimentIdentities(dto.key, dto.variants.map { it.name })
        validateUniqueKey(dto.key)
        validateWeights(dto.variants.map { it.weight })
        validateGoal(dto.goalEventName)
        validateOperations(dto.description, dto.trafficAllocation, dto.startsAt, dto.endsAt, dto.guardrailEventNames, dto.goalEventName)
        validateRules(dto.targetingRules.map { it.expression })

        val experiment = ExperimentEntity(
            key = dto.key,
            description = dto.description,
            goalEventName = dto.goalEventName.trim(),
            status = ExperimentStatus.DRAFT,
            trafficAllocation = dto.trafficAllocation, startsAt = dto.startsAt, endsAt = dto.endsAt,
            guardrailEventNames = dto.guardrailEventNames.toMutableSet(),
            layerKey = dto.layerKey, layerStart = dto.layerStart, layerEnd = dto.layerEnd, stickyBucketing = dto.stickyBucketing
        )

        dto.variants.forEach { variant ->
            experiment.addVariant(VariantEntity(name = variant.name, weight = variant.weight))
        }

        dto.targetingRules
            .filter { it.expression.isNotBlank() }
            .forEach { rule ->
                experiment.addTargetingRule(TargetingRuleEntity(expression = rule.expression))
            }

        return saveWithHistory(experiment, "CREATE", null)
    }

    fun updateExperiment(id: Long, dto: ExperimentUpdateDto): ExperimentEntity {
        population.lock()
        val experiment = findForUpdate(id)
        val before = snapshot(experiment)
        val locked = isLocked(experiment)
        validateOperations(dto.description, dto.trafficAllocation, dto.startsAt, dto.endsAt, dto.guardrailEventNames, dto.goalEventName)

        // Legacy invalid definitions must still be stoppable without changing their frozen settings.
        if (!locked || dto.status in setOf(ExperimentStatus.ACTIVE, ExperimentStatus.SCHEDULED)) {
            validateExperimentIdentities(dto.key, dto.variants.map { it.name })
            validateWeights(dto.variants.map { it.weight })
            validateGoal(dto.goalEventName)
            validateRules(dto.targetingRules.map { it.expression })
        }
        if (experiment.key != dto.key) validateUniqueKey(dto.key)

        val rules = dto.targetingRules.map { it.expression }.let { if (locked) it else it.filter(String::isNotBlank) }
        if (locked) {
            require(experiment.layerKey == dto.layerKey && experiment.layerStart == dto.layerStart && experiment.layerEnd == dto.layerEnd &&
                experiment.stickyBucketing == dto.stickyBucketing) { "시작·예약한 실험의 레이어 범위와 배정 유지 정책은 변경할 수 없습니다." }
            require(experiment.key == dto.key && experiment.goalEventName.orEmpty() == dto.goalEventName &&
                experiment.variants.map { it.name to it.weight } == dto.variants.map { it.name to it.weight } &&
                experiment.targetingRules.map { it.expression } == rules) {
                "시작한 실험의 키, 목표 이벤트, 변형 이름·순서·가중치, 타겟팅은 변경할 수 없습니다. 새 실험을 생성하세요."
            }
            require(java.util.Objects.equals(experiment.startsAt, dto.startsAt) && java.util.Objects.equals(experiment.endsAt, dto.endsAt) &&
                experiment.guardrailEventNames == dto.guardrailEventNames) { "시작·예약한 실험의 기간과 가드레일 지표는 변경할 수 없습니다." }
            require(dto.trafficAllocation >= experiment.trafficAllocation &&
                (experiment.status != ExperimentStatus.ENDED || dto.trafficAllocation == experiment.trafficAllocation)) {
                "참여 비율은 종료 전까지 확대만 가능합니다. 축소하거나 종료 후 변경하려면 새 실험을 생성하세요."
            }
        }
        validateTransition(experiment, dto.status)
        validateActivation(dto.status, dto.startsAt, dto.endsAt)
        population.validateLayer(id, dto.layerKey, dto.layerStart, dto.layerEnd)

        experiment.key = dto.key
        experiment.description = dto.description
        if (!locked) experiment.goalEventName = dto.goalEventName.trim()
        experiment.status = dto.status
        experiment.configurationLocked = locked || dto.status != ExperimentStatus.DRAFT
        experiment.trafficAllocation = dto.trafficAllocation
        experiment.startsAt = dto.startsAt
        experiment.endsAt = dto.endsAt
        experiment.layerKey = dto.layerKey
        experiment.layerStart = dto.layerStart
        experiment.layerEnd = dto.layerEnd
        experiment.stickyBucketing = dto.stickyBucketing
        if (experiment.guardrailEventNames != dto.guardrailEventNames) {
            experiment.guardrailEventNames.clear()
            experiment.guardrailEventNames.addAll(dto.guardrailEventNames)
        }
        if (experiment.variants.map { it.name to it.weight } != dto.variants.map { it.name to it.weight }) {
            experiment.variants.clear()
            dto.variants.forEach { variant ->
                experiment.addVariant(VariantEntity(name = variant.name, weight = variant.weight))
            }
        }

        if (experiment.targetingRules.map { it.expression } != rules) {
            experiment.targetingRules.clear()
            rules.forEach { experiment.addTargetingRule(TargetingRuleEntity(expression = it)) }
        }

        return saveWithHistory(experiment, "UPDATE", before)
    }

    fun deleteExperiment(id: Long) {
        population.lock()
        val experiment = findForUpdate(id)
        require(!isLocked(experiment)) { "설정이 잠긴 실험은 삭제할 수 없습니다. 종료 상태로 보존하세요." }
        changeRepository.save(ExperimentChangeEntity(experimentId = id, experimentKey = experiment.key,
            action = "DELETE", beforeSnapshot = snapshot(experiment), afterSnapshot = null, actor = currentActor()))
        experimentRepository.delete(experiment)
    }

    @Transactional(readOnly = true)
    fun getExperimentById(id: Long): ExperimentEntity = findByIdOrThrow(id)

    @Transactional(readOnly = true)
    fun isConfigurationLocked(id: Long): Boolean = isLocked(findByIdOrThrow(id))

    @Transactional(readOnly = true)
    fun getAllExperiments(): List<ExperimentEntity> = experimentRepository.findAll()

    @Transactional(readOnly = true)
    fun searchExperiments(
        keyword: String?,
        status: ExperimentStatus?,
        pageable: Pageable
    ): Page<ExperimentEntity> {
        return when {
            !keyword.isNullOrBlank() && status != null ->
                experimentRepository.findByKeyContainingAndStatus(keyword, status, pageable)
            !keyword.isNullOrBlank() ->
                experimentRepository.findByKeyContaining(keyword, pageable)
            status != null ->
                experimentRepository.findByStatus(status, pageable)
            else ->
                experimentRepository.findAll(pageable)
        }
    }

    fun startExperiment(id: Long): ExperimentEntity {
        population.lock() // Audit IDs must follow commit order for SSE revisions.
        val experiment = findForUpdate(id)
        val before = snapshot(experiment)
        validateTransition(experiment, ExperimentStatus.ACTIVE)
        validateExperimentIdentities(experiment.key, experiment.variants.map { it.name })
        validateWeights(experiment.variants.map { it.weight })
        validateGoal(experiment.goalEventName.orEmpty())
        validateActivation(ExperimentStatus.ACTIVE, experiment.startsAt, experiment.endsAt)
        validateRules(experiment.targetingRules.map { it.expression })
        validateOperations(experiment.description, experiment.trafficAllocation, experiment.startsAt, experiment.endsAt,
            experiment.guardrailEventNames, experiment.goalEventName.orEmpty())
        experiment.status = ExperimentStatus.ACTIVE
        experiment.configurationLocked = true
        return saveWithHistory(experiment, "START", before)
    }

    fun pauseExperiment(id: Long): ExperimentEntity {
        population.lock() // Audit IDs must follow commit order for SSE revisions.
        val experiment = findForUpdate(id)
        val before = snapshot(experiment)
        validateTransition(experiment, ExperimentStatus.PAUSED)
        experiment.status = ExperimentStatus.PAUSED
        experiment.configurationLocked = true
        return saveWithHistory(experiment, "PAUSE", before)
    }

    @Transactional(readOnly = true)
    fun getChangeHistory(id: Long): List<ExperimentChangeEntity> =
        changeRepository.findTop50ByExperimentIdOrderByIdDesc(id)

    private fun findForUpdate(id: Long): ExperimentEntity =
        experimentRepository.findForUpdate(id) ?: throw ExperimentNotFoundException(id)

    private fun isLocked(experiment: ExperimentEntity): Boolean = experiment.configurationLocked ||
        experiment.status != ExperimentStatus.DRAFT || impressionRepository.existsByExperimentKey(experiment.key)

    private fun validateTransition(experiment: ExperimentEntity, target: ExperimentStatus) {
        require(experiment.status != ExperimentStatus.ENDED || target == ExperimentStatus.ENDED) {
            "종료한 실험은 재시작할 수 없습니다. 새 실험을 생성하세요."
        }
        // A predeclared analysis plan locks the design while it is still an unexposed DRAFT.
        // Remaining in DRAFT for a description edit is distinct from returning after a start.
        require(target != ExperimentStatus.DRAFT || (experiment.status == ExperimentStatus.DRAFT &&
            !impressionRepository.existsByExperimentKey(experiment.key))) {
            "시작했거나 노출이 있는 실험은 DRAFT로 되돌릴 수 없습니다."
        }
    }

    private fun snapshot(experiment: ExperimentEntity): String = mapper.writeValueAsString(linkedMapOf(
        "key" to experiment.key, "description" to experiment.description, "goalEventName" to experiment.goalEventName,
        "status" to experiment.status, "configurationLocked" to experiment.configurationLocked,
        "trafficAllocation" to experiment.trafficAllocation, "startsAt" to experiment.startsAt?.toString(),
        "endsAt" to experiment.endsAt?.toString(), "guardrailEventNames" to experiment.guardrailEventNames.sorted(),
        "layerKey" to experiment.layerKey, "layerStart" to experiment.layerStart, "layerEnd" to experiment.layerEnd,
        "stickyBucketing" to experiment.stickyBucketing,
        "variants" to experiment.variants.map { linkedMapOf("name" to it.name, "weight" to it.weight) },
        "targetingRules" to experiment.targetingRules.map { it.expression }
    ))

    private fun saveWithHistory(experiment: ExperimentEntity, action: String, before: String?, actor: String = currentActor()): ExperimentEntity {
        val after = snapshot(experiment)
        if (before == after) return experiment
        experiment.updatedAt = java.time.LocalDateTime.now()
        val saved = experimentRepository.save(experiment)
        changeRepository.save(ExperimentChangeEntity(experimentId = requireNotNull(saved.id), experimentKey = saved.key,
            action = action, beforeSnapshot = before, afterSnapshot = after, actor = actor))
        return saved
    }

    fun advanceSchedule(id: Long, now: java.time.LocalDateTime) {
        population.lock() // Audit IDs must follow commit order for SSE revisions.
        val experiment = experimentRepository.findForUpdate(id) ?: return
        val before = snapshot(experiment)
        when {
            experiment.status in setOf(ExperimentStatus.SCHEDULED, ExperimentStatus.ACTIVE, ExperimentStatus.PAUSED) &&
                experiment.endsAt?.let { now >= it } == true -> experiment.status = ExperimentStatus.ENDED
            experiment.status == ExperimentStatus.SCHEDULED && experiment.startsAt?.let { now >= it } == true -> {
                validateExperimentIdentities(experiment.key, experiment.variants.map { it.name })
                validateWeights(experiment.variants.map { it.weight })
                validateRules(experiment.targetingRules.map { it.expression })
                validateGoal(experiment.goalEventName.orEmpty())
                validateOperations(experiment.description, experiment.trafficAllocation, experiment.startsAt, experiment.endsAt,
                    experiment.guardrailEventNames, experiment.goalEventName.orEmpty())
                experiment.status = ExperimentStatus.ACTIVE
            }
            else -> return
        }
        experiment.configurationLocked = true
        saveWithHistory(experiment, "SCHEDULE", before, "system:scheduler")
    }

    private fun currentActor(): String = org.springframework.security.core.context.SecurityContextHolder.getContext()
        .authentication?.takeIf { it.isAuthenticated && it !is org.springframework.security.authentication.AnonymousAuthenticationToken }
        ?.name ?: "system"

    private fun validateRules(rules: List<String>) {
        require(rules.size <= 100) { "타겟팅 규칙은 최대 100개입니다." }
        rules.forEach(io.github.silbaram.prism.core.targeting.RuleEvaluator::validateSyntax)
    }

    private fun validateOperations(description: String, allocation: Int, start: java.time.LocalDateTime?,
                                   end: java.time.LocalDateTime?, guardrails: Set<String>, goal: String) {
        require(description.length <= 255) { "설명은 255자 이하여야 합니다." }
        require(allocation in 0..100) { "참여 비율은 0–100%여야 합니다." }
        require(start == null || end == null || start < end) { "종료 시각은 시작 시각 이후여야 합니다." }
        listOfNotNull(start, end).forEach {
            val instant = it.toInstant(java.time.ZoneOffset.UTC)
            require(instant >= java.time.Instant.ofEpochSecond(1) && instant <= java.time.Instant.ofEpochSecond(Int.MAX_VALUE.toLong()) &&
                it.nano % 1000 == 0) { "시각은 MySQL TIMESTAMP 범위 내의 마이크로초 정밀도 UTC 값이어야 합니다." }
        }
        require(guardrails.size <= 20 && guardrails.all { it.isNotBlank() && it.length <= 255 && it == it.trim() && it != goal.trim() }) {
            "가드레일은 목표와 다른 1–255자 이벤트 이름을 최대 20개 지정하세요."
        }
    }

    private fun validateActivation(status: ExperimentStatus, start: java.time.LocalDateTime?, end: java.time.LocalDateTime?) {
        val now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        if (status in setOf(ExperimentStatus.SCHEDULED, ExperimentStatus.ACTIVE)) {
            require(end == null || end > now) { "이미 종료 시각이 지난 실험은 시작·예약할 수 없습니다." }
        }
        if (status == ExperimentStatus.SCHEDULED) require(start != null) { "예약 상태에는 시작 시각이 필요합니다." }
        if (status == ExperimentStatus.ACTIVE) require(start == null || start <= now) { "미래에 시작할 실험은 SCHEDULED 상태로 저장하세요." }
    }

    private fun findByIdOrThrow(id: Long): ExperimentEntity =
        experimentRepository.findById(id).orElseThrow { ExperimentNotFoundException(id) }

    private fun validateUniqueKey(key: String) {
        if (experimentRepository.findByKey(key) != null) {
            throw DuplicateExperimentKeyException(key)
        }
    }

    private fun validateWeights(weights: List<Int>) {
        try {
            validateVariantWeights(weights)
        } catch (exception: InvalidVariantWeightsException) {
            throw InvalidVariantWeightException(exception.totalWeight)
        }
    }

    private fun validateGoal(goal: String) {
        require(goal.isNotBlank() && goal.trim().length <= 255) { "목표 이벤트는 1–255자여야 합니다." }
    }
}
