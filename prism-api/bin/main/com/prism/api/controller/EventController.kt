
package com.prism.api.controller

import com.prism.api.service.LogService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/events")
class EventController(
    private val logService: LogService
) {

    @PostMapping("/conversion")
    fun trackConversion(@RequestBody request: ConversionRequest) {
        logService.logConversion(
            experimentKey = request.experimentKey,
            userId = request.userId,
            eventName = request.eventName
        )
    }
}

data class ConversionRequest(
    val experimentKey: String,
    val userId: String,
    val eventName: String
)
