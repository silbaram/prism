package io.github.silbaram.prism.api.traffic.application.service

import io.github.silbaram.prism.api.traffic.application.port.`in`.AssignVariantCommand
import io.github.silbaram.prism.api.traffic.application.port.`in`.AssignVariantResult
import io.github.silbaram.prism.api.traffic.application.port.`in`.AssignVariantUseCase
import io.github.silbaram.prism.api.traffic.application.port.out.LoadExperimentPort
import io.github.silbaram.prism.api.traffic.application.port.out.RecordImpressionPort
import io.github.silbaram.prism.core.splitter.TrafficSplitter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 변형 할당 서비스 (Application Service / Use Case Implementation).
 *
 * 이 서비스는 헥사고날 아키텍처의 Application Layer에 위치하며,
 * 사용자를 A/B 테스트 실험의 변형에 할당하는 비즈니스 로직을 담당합니다.
 *
 * ## 책임
 * - 실험 조회 (Outbound Port를 통해)
 * - 트래픽 분배 알고리즘 적용 (Domain Layer의 TrafficSplitter 사용)
 * - 노출 이벤트 기록 (Outbound Port를 통해)
 * - 비즈니스 규칙 및 에러 처리
 *
 * ## 의존성 역전 원칙 (DIP)
 * 이 서비스는 구체적인 구현이 아닌 Port 인터페이스에 의존하므로,
 * 인프라 계층의 변경에 영향을 받지 않습니다.
 */
@Service
class AssignVariantService(
    private val loadExperimentPort: LoadExperimentPort,
    private val recordImpressionPort: RecordImpressionPort
) : AssignVariantUseCase {

    /**
     * 사용자를 실험의 변형에 할당합니다.
     *
     * ## 처리 흐름
     * 1. 실험 조회 - 실험이 존재하고 활성화 상태인지 확인
     * 2. 변형 할당 - TrafficSplitter를 사용하여 사용자를 변형에 할당
     * 3. 노출 기록 - 성공적으로 할당된 경우 노출 이벤트 기록
     * 4. 결과 반환 - 할당 결과를 도메인 모델로 반환
     *
     * ## 에러 처리
     * - 실험이 존재하지 않으면 experimentNotFound 결과 반환
     * - 할당 실패 시에도 안전하게 처리
     *
     * @param command 할당 요청 커맨드
     * @return 할당 결과
     */
    @Transactional
    override fun assignVariant(command: AssignVariantCommand): AssignVariantResult {
        // 1단계: 실험 조회
        val experiment = loadExperimentPort.loadExperiment(command.experimentKey)
            ?: return AssignVariantResult.experimentNotFound(
                userId = command.userId,
                experimentKey = command.experimentKey
            )

        // 2단계: 변형 할당 (Domain Layer의 비즈니스 로직 사용)
        val variant = TrafficSplitter.assign(experiment, command.userId)
        val variantName = variant?.name ?: ""

        // 3단계: 노출 이벤트 기록 (응답 전에 커밋)
        if (variantName.isNotEmpty()) {
            recordImpressionPort.recordImpression(
                experimentKey = command.experimentKey,
                variantName = variantName,
                userId = command.userId
            )
        }

        // 4단계: 결과 반환
        return AssignVariantResult.success(
            userId = command.userId,
            experimentKey = command.experimentKey,
            variantName = variantName
        )
    }
}
