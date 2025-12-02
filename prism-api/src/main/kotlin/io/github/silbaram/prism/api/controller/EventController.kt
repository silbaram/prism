package io.github.silbaram.prism.api.controller

import io.github.silbaram.prism.api.service.LogService
import io.github.silbaram.prism.infrastructure.persistence.repository.ImpressionLogRepository
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/events")
class EventController(
    private val logService: LogService,
    private val impressionLogRepository: ImpressionLogRepository
) {

    @PostMapping("/conversion")
    fun trackConversion(@RequestBody request: ConversionRequest) {
        // 가장 최근의 impression을 조회하여 variant 정보 가져오기
        val impression = impressionLogRepository.findFirstByUserIdAndExperimentKeyOrderByTimestampDesc(
            request.userId,
            request.experimentKey
        )

        logService.logConversion(
            experimentKey = request.experimentKey,
            userId = request.userId,
            eventName = request.eventName,
            variant = impression?.variant
        )
    }
}

data class ConversionRequest(
    val experimentKey: String,
    val userId: String,
    val eventName: String
)
