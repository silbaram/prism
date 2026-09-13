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
    private val impressionRepository: ImpressionLogRepository
) {
    private val mapper = jacksonObjectMapper()

    fun createExperiment(dto: ExperimentCreateDto): ExperimentEntity {
        validateExperimentIdentities(dto.key, dto.variants.map { it.name })
        validateUniqueKey(dto.key)
        validateWeights(dto.variants.map { it.weight })
        validateGoal(dto.goalEventName)

        val experiment = ExperimentEntity(
            key = dto.key,
            description = dto.description,
            goalEventName = dto.goalEventName.trim(),
            status = ExperimentStatus.DRAFT
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
        val experiment = findForUpdate(id)
        val before = snapshot(experiment)
        val locked = isLocked(experiment)

        // Legacy invalid definitions must still be stoppable without changing their frozen settings.
        if (!locked || dto.status == ExperimentStatus.ACTIVE) {
            validateExperimentIdentities(dto.key, dto.variants.map { it.name })
            validateWeights(dto.variants.map { it.weight })
            validateGoal(dto.goalEventName)
        }
        if (experiment.key != dto.key) validateUniqueKey(dto.key)

        val rules = dto.targetingRules.map { it.expression }.let { if (locked) it else it.filter(String::isNotBlank) }
        if (locked) {
            require(experiment.key == dto.key && experiment.goalEventName.orEmpty() == dto.goalEventName &&
                experiment.variants.map { it.name to it.weight } == dto.variants.map { it.name to it.weight } &&
                experiment.targetingRules.map { it.expression } == rules) {
                "시작한 실험의 키, 목표 이벤트, 변형 이름·순서·가중치, 타겟팅은 변경할 수 없습니다. 새 실험을 생성하세요."
            }
        }
        validateTransition(experiment, dto.status)

        experiment.key = dto.key
        experiment.description = dto.description
        if (!locked) experiment.goalEventName = dto.goalEventName.trim()
        experiment.status = dto.status
        experiment.configurationLocked = locked || dto.status != ExperimentStatus.DRAFT
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
        val experiment = findForUpdate(id)
        require(!isLocked(experiment)) { "시작했거나 노출이 있는 실험은 삭제할 수 없습니다. 종료 상태로 보존하세요." }
        changeRepository.save(ExperimentChangeEntity(experimentId = id, experimentKey = experiment.key,
            action = "DELETE", beforeSnapshot = snapshot(experiment), afterSnapshot = null))
        experimentRepository.delete(experiment)
    }

    @Transactional(readOnly = true)
    fun getExperimentById(id: Long): ExperimentEntity = findByIdOrThrow(id)

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
        val experiment = findForUpdate(id)
        val before = snapshot(experiment)
        validateTransition(experiment, ExperimentStatus.ACTIVE)
        validateExperimentIdentities(experiment.key, experiment.variants.map { it.name })
        validateWeights(experiment.variants.map { it.weight })
        validateGoal(experiment.goalEventName.orEmpty())
        experiment.status = ExperimentStatus.ACTIVE
        experiment.configurationLocked = true
        return saveWithHistory(experiment, "START", before)
    }

    fun pauseExperiment(id: Long): ExperimentEntity {
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
        require(target != ExperimentStatus.DRAFT || !isLocked(experiment)) {
            "시작했거나 노출이 있는 실험은 DRAFT로 되돌릴 수 없습니다."
        }
    }

    private fun snapshot(experiment: ExperimentEntity): String = mapper.writeValueAsString(linkedMapOf(
        "key" to experiment.key, "description" to experiment.description, "goalEventName" to experiment.goalEventName,
        "status" to experiment.status, "configurationLocked" to experiment.configurationLocked,
        "variants" to experiment.variants.map { linkedMapOf("name" to it.name, "weight" to it.weight) },
        "targetingRules" to experiment.targetingRules.map { it.expression }
    ))

    private fun saveWithHistory(experiment: ExperimentEntity, action: String, before: String?): ExperimentEntity {
        val after = snapshot(experiment)
        if (before == after) return experiment
        experiment.updatedAt = java.time.LocalDateTime.now()
        val saved = experimentRepository.save(experiment)
        changeRepository.save(ExperimentChangeEntity(experimentId = requireNotNull(saved.id), experimentKey = saved.key,
            action = action, beforeSnapshot = before, afterSnapshot = after))
        return saved
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
