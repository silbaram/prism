package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.entities.VariantEntity
import io.github.silbaram.prism.infrastructure.persistence.repository.ExperimentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class ExperimentService(
    private val experimentRepository: ExperimentRepository
) {
    fun createExperiment(key: String, description: String, variants: List<VariantDto>): ExperimentEntity {
        if (experimentRepository.findByKey(key) != null) {
            throw IllegalArgumentException("Experiment with key $key already exists")
        }

        val totalWeight = variants.sumOf { it.weight }
        if (totalWeight != 100) {
            throw IllegalArgumentException("Total weight must be 100")
        }

        val experiment = ExperimentEntity(
            key = key,
            description = description,
            status = ExperimentStatus.DRAFT
        )

        variants.forEach { dto ->
            experiment.addVariant(VariantEntity(
                name = dto.name,
                weight = dto.weight
            ))
        }

        return experimentRepository.save(experiment)
    }

    fun startExperiment(id: Long): ExperimentEntity {
        val experiment = experimentRepository.findById(id).orElseThrow { IllegalArgumentException("Experiment not found") }
        experiment.status = ExperimentStatus.ACTIVE
        return experimentRepository.save(experiment)
    }

    fun pauseExperiment(id: Long): ExperimentEntity {
        val experiment = experimentRepository.findById(id).orElseThrow { IllegalArgumentException("Experiment not found") }
        experiment.status = ExperimentStatus.PAUSED
        return experimentRepository.save(experiment)
    }

    @Transactional(readOnly = true)
    fun getAllExperiments(): List<ExperimentEntity> {
        return experimentRepository.findAll()
    }
}

data class VariantDto(val name: String, val weight: Int)
