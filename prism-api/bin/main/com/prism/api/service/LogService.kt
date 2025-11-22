
package com.prism.api.service

import com.prism.api.domain.ConversionEntity
import com.prism.api.domain.ImpressionEntity
import com.prism.api.repository.ConversionRepository
import com.prism.api.repository.ImpressionRepository
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LogService(
    private val impressionRepository: ImpressionRepository,
    private val conversionRepository: ConversionRepository
) {

    @Async
    @Transactional
    fun logImpression(experimentKey: String, variant: String, userId: String) {
        val impression = ImpressionEntity(
            experimentKey = experimentKey,
            variant = variant,
            userId = userId
        )
        impressionRepository.save(impression)
    }

    @Async
    @Transactional
    fun logConversion(experimentKey: String, userId: String, eventName: String) {
        val conversion = ConversionEntity(
            experimentKey = experimentKey,
            userId = userId,
            eventName = eventName
        )
        conversionRepository.save(conversion)
    }
}
