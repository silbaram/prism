
package com.prism.api.service

import io.github.silbaram.prism.infrastructure.persistence.entities.ConversionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ImpressionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.repository.ConversionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.repository.ImpressionLogRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LogService(
    private val impressionRepository: ImpressionLogRepository,
    private val conversionRepository: ConversionLogRepository
) {

    @Async
    @Transactional
    fun logImpression(experimentKey: String, variant: String, userId: String) {
        val impression = ImpressionLogEntity(
            experimentKey = experimentKey,
            variant = variant,
            userId = userId
        )
        impressionRepository.save(impression)
    }

    @Async
    @Transactional
    fun logConversion(experimentKey: String, userId: String, eventName: String) {
        val conversion = ConversionLogEntity(
            experimentKey = experimentKey,
            userId = userId,
            eventName = eventName
        )
        conversionRepository.save(conversion)
    }
}
