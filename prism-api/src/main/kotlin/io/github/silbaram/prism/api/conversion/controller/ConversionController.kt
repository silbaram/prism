package io.github.silbaram.prism.api.conversion.controller

import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionCommand
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionResult
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionUseCase
import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionRequest
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v1/conversions")
class ConversionController(private val trackConversionUseCase: TrackConversionUseCase) {
    @Deprecated("Use POST /v1/events; retained for remote clients")
    @PostMapping
    fun trackConversion(@RequestBody request: ConversionRequest): ConversionResponse {
        val result = trackConversionUseCase.trackConversion(
            TrackConversionCommand(request.userId, request.experimentKey, request.eventName))
        val code = when (result) {
            is TrackConversionResult.Recorded -> ResponseCode.SUCCESS
            is TrackConversionResult.Rejected -> ResponseCode.IMPRESSION_NOT_FOUND
        }
        return ConversionResponse(request.userId, request.experimentKey, request.eventName,
            (result as? TrackConversionResult.Recorded)?.variantName, code.code, code.message)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("message" to (exception.message ?: "Invalid conversion request"))
}
