package io.github.silbaram.prism.api.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.silbaram.prism.common.rest.dto.config.*
import io.github.silbaram.prism.core.model.validateExperimentIdentities
import io.github.silbaram.prism.core.model.validateVariantWeights
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest
import java.security.MessageDigest
import java.time.Duration
import org.slf4j.LoggerFactory

@Service
class ConfigSnapshotLoader(private val experiments: ExperimentRepository) {
    private val logger = LoggerFactory.getLogger(javaClass)
    @Transactional(readOnly = true)
    fun load(): ConfigResponse {
        val definitions = experiments.findAllByStatus(ExperimentStatus.ACTIVE).sortedBy { it.key }.mapNotNull { entity ->
            // Historical rows can predate admin validation. Quarantine only the invalid experiment.
            try {
                validateExperimentIdentities(entity.key, entity.variants.map { it.name })
                validateVariantWeights(entity.variants.map { it.weight })
            } catch (exception: IllegalArgumentException) {
                logger.warn("Excluding invalid active experiment: id={}, reason={}", entity.id, exception.message)
                return@mapNotNull null
            }
            ExperimentConfig(entity.key, entity.status.name,
                entity.variants.map { VariantConfig(it.name, it.weight) },
                entity.targetingRules.map { it.expression }, entity.goalEventName)
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(jacksonObjectMapper().writeValueAsBytes(definitions))
            .joinToString("") { "%02x".format(it) }
        return ConfigResponse(hash, definitions)
    }
}

@Service
class ConfigService(
    private val loader: ConfigSnapshotLoader,
    @Value("\${prism.config.cache-ttl:PT5S}") ttl: Duration
) {
    init { require(!ttl.isNegative && !ttl.isZero) { "Config cache TTL must be positive" } }
    private val snapshots = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(ttl)
        .build<String, ConfigResponse>()

    fun snapshot(): ConfigResponse = requireNotNull(snapshots.get("active") { loader.load() })
}

@RestController
class ConfigController(private val configs: ConfigService) {
    @GetMapping("/v1/config")
    fun config(request: WebRequest): ResponseEntity<ConfigResponse> {
        val snapshot = configs.snapshot()
        val etag = "\"${snapshot.version}\""
        if (request.checkNotModified(etag)) return ResponseEntity.status(304).eTag(etag).build()
        return ResponseEntity.ok().eTag(etag).header("Cache-Control", "no-cache").body(snapshot)
    }
}
