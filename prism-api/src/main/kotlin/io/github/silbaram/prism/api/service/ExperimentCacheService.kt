package io.github.silbaram.prism.api.service

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.repository.ExperimentRepository
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class ExperimentCacheService(
    private val experimentRepository: ExperimentRepository
) {
    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(1000)
        .build<String, Experiment>()

    fun getExperiment(key: String): Experiment? {
        val cached = cache.getIfPresent(key)
        if (cached != null) {
            return cached
        }

        val entity = experimentRepository.findByKey(key)
        if (entity != null && entity.status == ExperimentStatus.ACTIVE) {
            val experiment = Experiment(
                key = entity.key,
                variants = entity.variants.map { v ->
                    Variant(v.name, v.weight)
                }
            )
            cache.put(key, experiment)
            return experiment
        }
        return null
    }
}
