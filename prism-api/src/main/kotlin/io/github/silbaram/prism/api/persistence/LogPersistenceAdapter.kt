package io.github.silbaram.prism.api.persistence

import io.github.silbaram.prism.api.conversion.application.port.out.Impression
import io.github.silbaram.prism.api.conversion.application.port.out.LoadImpressionPort
import io.github.silbaram.prism.api.conversion.application.port.out.RecordConversionPort
import io.github.silbaram.prism.api.traffic.application.port.out.RecordImpressionPort
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ConversionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ImpressionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ConversionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ImpressionLogRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional
class LogPersistenceAdapter(
    private val impressions: ImpressionLogRepository,
    private val conversions: ConversionLogRepository
) : LoadImpressionPort, RecordImpressionPort, RecordConversionPort {
    @Transactional(readOnly = true)
    override fun loadLatestImpression(userId: String, experimentKey: String): Impression? =
        impressions.findFirstByUserIdAndExperimentKeyOrderByIdDesc(userId, experimentKey)
            ?.let { Impression(requireNotNull(it.id), it.variant) }

    // Synchronous persistence: an assignment response is sent only after its exposure commits.
    override fun recordImpression(experimentKey: String, variantName: String, userId: String) {
        impressions.save(ImpressionLogEntity(experimentKey = experimentKey, variant = variantName, userId = userId))
    }

    override fun recordConversion(experimentKey: String, userId: String, eventName: String, impression: Impression) {
        conversions.save(ConversionLogEntity(experimentKey = experimentKey, variant = impression.variantName,
            userId = userId, eventName = eventName, impressionId = impression.id))
    }
}
