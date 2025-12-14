package io.github.silbaram.prism.api.conversion.controller

import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionCommand
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionUseCase
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionRequest
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionResponse
import io.github.silbaram.prism.common.rest.ResponseCode
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 전환 추적 컨트롤러 (Inbound Adapter).
 *
 * 헥사고날 아키텍처의 Inbound Adapter로, REST API를 통해
 * 외부 요청을 받아 Use Case로 전달하는 역할을 합니다.
 *
 * ## 책임
 * - HTTP 요청 파라미터 검증 및 파싱
 * - Use Case 호출
 * - 도메인 결과를 REST 응답(DTO)으로 변환
 *
 * ## 비즈니스 로직 분리
 * 컨트롤러는 비즈니스 로직을 포함하지 않으며, 단순히
 * 웹 계층과 애플리케이션 계층을 연결하는 어댑터 역할만 수행합니다.
 */
@RestController
@RequestMapping("/v1/conversions")
class ConversionController(
    private val trackConversionUseCase: TrackConversionUseCase
) {

    /**
     * 사용자의 전환 이벤트를 추적합니다.
     *
     * ## API 명세
     * - Method: POST
     * - Path: /v1/conversions
     * - Body: ConversionRequest
     *
     * ## 처리 흐름
     * 1. 요청 바디를 Command 객체로 변환
     * 2. Use Case 호출
     * 3. 도메인 결과를 REST 응답으로 변환
     *
     * @param request 전환 이벤트 요청
     * @return 전환 추적 결과
     */
    @PostMapping
    fun trackConversion(@RequestBody request: ConversionRequest): ConversionResponse {
        // 1단계: Command 생성
        val command = TrackConversionCommand(
            userId = request.userId,
            experimentKey = request.experimentKey,
            eventName = request.eventName
        )

        // 2단계: Use Case 호출 (비즈니스 로직은 Application Layer에서 처리)
        val result = trackConversionUseCase.trackConversion(command)

        // 3단계: 도메인 결과를 REST 응답으로 변환
        return ConversionResponse(
            userId = result.userId,
            experimentKey = result.experimentKey,
            eventName = result.eventName,
            variant = result.variantName,
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = result.message
        )
    }
}
