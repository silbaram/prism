package io.github.silbaram.prism.admin.api.service

import io.github.silbaram.prism.admin.api.domain.ExperimentEntity
import io.github.silbaram.prism.admin.api.domain.ExperimentStatus
import io.github.silbaram.prism.admin.api.domain.VariantEntity
import io.github.silbaram.prism.admin.api.repository.ExperimentRepository
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
