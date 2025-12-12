package io.github.silbaram.prism.api.traffic.controller

import io.github.silbaram.prism.api.traffic.application.port.`in`.AssignVariantCommand
import io.github.silbaram.prism.api.traffic.application.port.`in`.AssignVariantUseCase
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.ResponseCode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 트래픽 분배 컨트롤러 (Inbound Adapter).
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
@RequestMapping("/v1/assign")
class TrafficController(
    private val assignVariantUseCase: AssignVariantUseCase
) {

    /**
     * 사용자를 A/B 테스트 변형에 할당합니다.
     *
     * ## API 명세
     * - Method: GET
     * - Path: /v1/assign
     * - Parameters:
     *   - userId: 사용자 고유 식별자 (필수)
     *   - experimentKey: 실험 키 (필수)
     *
     * ## 처리 흐름
     * 1. 요청 파라미터를 Command 객체로 변환
     * 2. Use Case 호출
     * 3. 도메인 결과를 REST 응답으로 변환
     *
     * @param userId 사용자 고유 식별자
     * @param experimentKey 실험 키
     * @return 변형 할당 결과
     */
    @GetMapping
    fun assign(
        @RequestParam userId: String,
        @RequestParam experimentKey: String
    ): AssignmentResponse {
        // 1단계: Command 생성
        val command = AssignVariantCommand(
            userId = userId,
            experimentKey = experimentKey
        )

        // 2단계: Use Case 호출 (비즈니스 로직은 Application Layer에서 처리)
        val result = assignVariantUseCase.assignVariant(command)

        // 3단계: 도메인 결과를 REST 응답으로 변환
        return if (result.success) {
            AssignmentResponse(
                userId = result.userId,
                experimentKey = result.experimentKey,
                variant = result.variantName,
                resultCode = ResponseCode.SUCCESS.code,
                resultMessage = ResponseCode.SUCCESS.message
            )
        } else {
            AssignmentResponse(
                userId = result.userId,
                experimentKey = result.experimentKey,
                variant = null,
                resultCode = ResponseCode.EXPERIMENT_NOT_FOUND.code,
                resultMessage = ResponseCode.EXPERIMENT_NOT_FOUND.message
            )
        }
    }
}
