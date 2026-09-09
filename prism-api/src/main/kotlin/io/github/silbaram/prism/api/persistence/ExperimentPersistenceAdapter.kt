package io.github.silbaram.prism.api.persistence

import io.github.silbaram.prism.api.traffic.application.port.out.LoadExperimentPort
import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.core.targeting.TargetingRule
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ExperimentPersistenceAdapter(private val experiments: ExperimentRepository) : LoadExperimentPort {
    @Transactional(readOnly = true)
    override fun loadExperiment(experimentKey: String): Experiment? {
        val entity = experiments.findByKey(experimentKey)
            ?.takeIf { it.status == ExperimentStatus.ACTIVE } ?: return null
        return Experiment(entity.key, entity.variants.map { Variant(it.name, it.weight) },
            entity.targetingRules.map { TargetingRule(it.expression) })
    }
}
