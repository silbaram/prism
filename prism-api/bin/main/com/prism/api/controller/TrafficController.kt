package com.prism.api.controller

import com.prism.api.service.ExperimentCacheService
import com.prism.core.splitter.TrafficSplitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/assign")
class TrafficController(private val experimentCacheService: ExperimentCacheService) {

    @GetMapping
    fun assign(
            @RequestParam userId: String,
            @RequestParam experimentKey: String
    ): AssignmentResponse {
        val experiment =
                experimentCacheService.getExperiment(experimentKey)
                        ?: throw IllegalArgumentException("Experiment not found or not active")

        val variant = TrafficSplitter.assign(experiment, userId)

        return AssignmentResponse(
                userId = userId,
                experimentKey = experimentKey,
                variant = variant?.name ?: "control"
        )
    }
}

data class AssignmentResponse(val userId: String, val experimentKey: String, val variant: String)
