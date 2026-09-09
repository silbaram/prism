package io.github.silbaram.prism.api.traffic.controller

import io.github.silbaram.prism.api.conversion.application.port.out.LoadImpressionPort
import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import org.springframework.web.bind.annotation.*

/** Read-only lookup. Never assigns a variant or creates an exposure during conversion tracking. */
@RestController
@RequestMapping("/v1/assignments")
class AssignmentLookupController(private val impressions: LoadImpressionPort) {
    @GetMapping
    fun lookup(@RequestParam userId: String, @RequestParam experimentKey: String): AssignmentResponse {
        val impression = impressions.loadLatestImpression(userId, experimentKey)
        val code = if (impression == null) ResponseCode.IMPRESSION_NOT_FOUND else ResponseCode.SUCCESS
        return AssignmentResponse(userId, experimentKey, impression?.variantName, code.code, code.message)
    }
}
