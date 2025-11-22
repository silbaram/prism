
package com.prism.api.service

import com.github.benmanes.caffeine.cache.Caffeine
import com.prism.api.repository.ExperimentRepository
import com.prism.core.model.Experiment
import com.prism.core.model.Variant
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
        if (entity != null && entity.status == "ACTIVE") {
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
