
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
        return cache.get(key) { k ->
            experimentRepository.findByKey(k)?.let { entity ->
                if (entity.status != "ACTIVE") return@let null
                
                Experiment(
                    key = entity.key,
                    variants = entity.variants.map { v ->
                        Variant(v.name, v.weight)
                    }
                )
            }
        }
    }
}
