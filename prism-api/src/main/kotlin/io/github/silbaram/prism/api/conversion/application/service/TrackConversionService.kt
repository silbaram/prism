package io.github.silbaram.prism.api.conversion.application.service

import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionCommand
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionResult
import io.github.silbaram.prism.api.conversion.application.port.`in`.TrackConversionUseCase
import io.github.silbaram.prism.api.conversion.application.port.out.LoadImpressionPort
import io.github.silbaram.prism.api.conversion.application.port.out.RecordConversionPort
import org.springframework.stereotype.Service

/**
 * 전환 추적 서비스 (Application Service / Use Case Implementation).
 *
 * 이 서비스는 헥사고날 아키텍처의 Application Layer에 위치하며,
 * 사용자의 전환 이벤트를 추적하고 기록하는 비즈니스 로직을 담당합니다.
 *
 * ## 책임
 * - 가장 최근 노출 로그 조회 (Outbound Port를 통해)
 * - 전환 이벤트 기록 (Outbound Port를 통해)
 * - 비즈니스 규칙 및 에러 처리
 *
 * ## 의존성 역전 원칙 (DIP)
 * 이 서비스는 구체적인 구현이 아닌 Port 인터페이스에 의존하므로,
 * 인프라 계층의 변경에 영향을 받지 않습니다.
 */
@Service
class TrackConversionService(
    private val loadImpressionPort: LoadImpressionPort,
    private val recordConversionPort: RecordConversionPort
) : TrackConversionUseCase {

    /**
     * 사용자의 전환 이벤트를 추적합니다.
     *
     * ## 처리 흐름
     * 1. 가장 최근 노출 로그 조회 - 사용자가 어떤 변형을 할당받았는지 확인
     * 2. 전환 이벤트 기록 - 노출 로그의 변형 정보와 함께 전환 기록 (비동기)
     * 3. 결과 반환 - 추적 결과를 도메인 모델로 반환
     *
     * ## 비즈니스 규칙
     * - 노출 로그가 없어도 전환 이벤트는 기록됩니다 (variantName=null)
     * - 이는 직접 전환(direct conversion) 등의 케이스를 추적하기 위함입니다
     *
     * @param command 전환 추적 요청 커맨드
     * @return 추적 결과
     */
    override fun trackConversion(command: TrackConversionCommand): TrackConversionResult {
        // 1단계: 가장 최근 노출 로그 조회
        val impression = loadImpressionPort.loadLatestImpression(
            userId = command.userId,
            experimentKey = command.experimentKey
        )

        val variantName = impression?.variantName

        // 2단계: 전환 이벤트 기록 (비동기)
        recordConversionPort.recordConversion(
            experimentKey = command.experimentKey,
            userId = command.userId,
            eventName = command.eventName,
            variantName = variantName
        )

        // 3단계: 결과 반환
        return if (variantName != null) {
            TrackConversionResult.success(
                userId = command.userId,
                experimentKey = command.experimentKey,
                eventName = command.eventName,
                variantName = variantName
            )
        } else {
            TrackConversionResult.impressionNotFound(
                userId = command.userId,
                experimentKey = command.experimentKey,
                eventName = command.eventName
            )
        }
    }
}
