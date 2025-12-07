package io.github.silbaram.prism.api.controller

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.ResponseCode
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
            experimentCacheService.getExperiment(experimentKey) ?: return AssignmentResponse(
                userId = userId,
                experimentKey = experimentKey,
                variant = null,
                resultCode = ResponseCode.EXPERIMENT_NOT_FOUND.code,
                resultMessage = ResponseCode.EXPERIMENT_NOT_FOUND.message
            )

        val variant = TrafficSplitter.assign(experiment, userId)
        val variantName = variant?.name ?: ""

        // Impression 로그 기록
        logService.logImpression(experimentKey, variantName, userId)

        return AssignmentResponse(
            userId = userId,
            experimentKey = experimentKey,
            variant = variantName,
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = ResponseCode.SUCCESS.message
        )
    }
}


