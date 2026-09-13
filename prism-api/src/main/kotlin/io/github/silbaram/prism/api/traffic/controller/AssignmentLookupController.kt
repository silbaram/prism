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
    fun lookup(@RequestParam userId: String, @RequestParam experimentKey: String,
               @RequestParam(defaultValue = "RECORDED") order: AssignmentOrder): AssignmentResponse {
        val impression = when (order) {
            AssignmentOrder.RECORDED -> impressions.loadLatestImpression(userId, experimentKey)
            AssignmentOrder.OCCURRED_AT -> impressions.loadLatestOccurredImpression(userId, experimentKey)
        }
        val code = if (impression == null) ResponseCode.IMPRESSION_NOT_FOUND else ResponseCode.SUCCESS
        return AssignmentResponse(userId, experimentKey, impression?.variantName, code.code, code.message,
            impression?.configVersion, impression?.eventId)
    }
}

enum class AssignmentOrder { RECORDED, OCCURRED_AT }
