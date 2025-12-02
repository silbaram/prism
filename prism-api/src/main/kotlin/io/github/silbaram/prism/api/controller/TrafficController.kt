package io.github.silbaram.prism.api.controller

import io.github.silbaram.prism.api.service.ExperimentCacheService
import io.github.silbaram.prism.api.service.LogService
import io.github.silbaram.prism.core.splitter.TrafficSplitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/assign")
class TrafficController(
    private val experimentCacheService: ExperimentCacheService,
    private val logService: LogService
) {

    @GetMapping
    fun assign(
            @RequestParam userId: String,
            @RequestParam experimentKey: String
    ): AssignmentResponse {
        val experiment =
                experimentCacheService.getExperiment(experimentKey)
                        ?: throw IllegalArgumentException("Experiment not found or not active")

        val variant = TrafficSplitter.assign(experiment, userId)
        val variantName = variant?.name ?: "control"

        // Impression 로그 기록
        logService.logImpression(experimentKey, variantName, userId)

        return AssignmentResponse(
                userId = userId,
                experimentKey = experimentKey,
                variant = variantName
        )
    }
}

data class AssignmentResponse(val userId: String, val experimentKey: String, val variant: String)
