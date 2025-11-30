package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.admin.exception.DuplicateExperimentKeyException
import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import io.github.silbaram.prism.admin.exception.InvalidVariantWeightException
import io.github.silbaram.prism.admin.service.dto.ExperimentCreateDto
import io.github.silbaram.prism.admin.service.dto.ExperimentUpdateDto
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.entities.TargetingRuleEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.VariantEntity
import io.github.silbaram.prism.infrastructure.persistence.repository.ExperimentRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class ExperimentService(
    private val experimentRepository: ExperimentRepository
) {

    fun createExperiment(dto: ExperimentCreateDto): ExperimentEntity {
        validateUniqueKey(dto.key)
        validateVariantWeights(dto.variants.sumOf { it.weight })

        val experiment = ExperimentEntity(
            key = dto.key,
            description = dto.description,
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

        return experimentRepository.save(experiment)
    }

    fun updateExperiment(id: Long, dto: ExperimentUpdateDto): ExperimentEntity {
        val experiment = findByIdOrThrow(id)

        experiment.key = dto.key
        experiment.description = dto.description
        experiment.status = dto.status
        experiment.updatedAt = java.time.LocalDateTime.now()

        experiment.variants.clear()
        dto.variants.forEach { variant ->
            experiment.addVariant(VariantEntity(name = variant.name, weight = variant.weight))
        }

        experiment.targetingRules.clear()
        dto.targetingRules
            .filter { it.expression.isNotBlank() }
            .forEach { rule ->
                experiment.addTargetingRule(TargetingRuleEntity(expression = rule.expression))
            }

        return experimentRepository.save(experiment)
    }

    fun deleteExperiment(id: Long) {
        experimentRepository.deleteById(id)
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
        val experiment = findByIdOrThrow(id)
        experiment.status = ExperimentStatus.ACTIVE
        return experimentRepository.save(experiment)
    }

    fun pauseExperiment(id: Long): ExperimentEntity {
        val experiment = findByIdOrThrow(id)
        experiment.status = ExperimentStatus.PAUSED
        return experimentRepository.save(experiment)
    }

    private fun findByIdOrThrow(id: Long): ExperimentEntity =
        experimentRepository.findById(id).orElseThrow { ExperimentNotFoundException(id) }

    private fun validateUniqueKey(key: String) {
        if (experimentRepository.findByKey(key) != null) {
            throw DuplicateExperimentKeyException(key)
        }
    }

    private fun validateVariantWeights(totalWeight: Int) {
        if (totalWeight != 100) {
            throw InvalidVariantWeightException(totalWeight)
        }
    }
}
