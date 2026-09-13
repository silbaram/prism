package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.core.splitter.TrafficSplitter
import io.github.silbaram.prism.core.targeting.UserContext
import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class SimulatorService(
    private val experimentRepository: ExperimentRepository
) {

    fun simulateAssignment(experimentId: Long, userId: String): SimulationResult {
        require(userId.isNotBlank() && userId.length <= 255)
        val experimentEntity = experimentRepository.findById(experimentId)
            .orElseThrow { ExperimentNotFoundException(experimentId) }

        val experiment = Experiment(
            key = experimentEntity.key,
            variants = experimentEntity.variants.map { Variant(it.name, it.weight) },
            targetingRules = emptyList(),
            trafficAllocation = experimentEntity.trafficAllocation,
            startsAt = experimentEntity.startsAt?.toInstant(java.time.ZoneOffset.UTC),
            endsAt = experimentEntity.endsAt?.toInstant(java.time.ZoneOffset.UTC)
        )

        val assignedVariant = TrafficSplitter.assign(experiment, userId, UserContext(emptyMap()))

        return SimulationResult(
            experimentKey = experimentEntity.key,
            userId = userId,
            assignedVariant = assignedVariant?.name
        )
    }

}

data class SimulationResult(
    val experimentKey: String,
    val userId: String,
    val assignedVariant: String?
)
