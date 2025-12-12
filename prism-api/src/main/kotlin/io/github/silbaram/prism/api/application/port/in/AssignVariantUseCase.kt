package io.github.silbaram.prism.api.application.port.`in`

/**
 * 변형 할당 유스케이스 (Inbound Port).
 *
 * 헥사고날 아키텍처의 Inbound Port로, 사용자를 A/B 테스트 실험의
 * 특정 변형에 할당하는 비즈니스 유스케이스를 정의합니다.
 *
 * 이 인터페이스는 비즈니스 로직의 진입점이며, 웹 컨트롤러나
 * 다른 어댑터에서 호출됩니다.
 */
interface AssignVariantUseCase {
    /**
     * 사용자를 실험의 변형에 할당합니다.
     *
     * @param command 할당 요청 커맨드
     * @return 할당 결과
     */
    fun assignVariant(command: AssignVariantCommand): AssignVariantResult
}

/**
 * 변형 할당 요청 커맨드.
 *
 * @property userId 사용자 고유 식별자
 * @property experimentKey 실험 키
 */
data class AssignVariantCommand(
    val userId: String,
    val experimentKey: String
)

/**
 * 변형 할당 결과.
 *
 * @property userId 사용자 고유 식별자
 * @property experimentKey 실험 키
 * @property variantName 할당된 변형 이름 (null이면 할당 실패)
 * @property success 할당 성공 여부
 * @property errorMessage 에러 메시지 (실패 시)
 */
data class AssignVariantResult(
    val userId: String,
    val experimentKey: String,
    val variantName: String?,
    val success: Boolean,
    val errorMessage: String? = null
) {
    companion object {
        fun success(userId: String, experimentKey: String, variantName: String) =
            AssignVariantResult(
                userId = userId,
                experimentKey = experimentKey,
                variantName = variantName,
                success = true
            )

        fun experimentNotFound(userId: String, experimentKey: String) =
            AssignVariantResult(
                userId = userId,
                experimentKey = experimentKey,
                variantName = null,
                success = false,
                errorMessage = "Experiment not found"
            )
    }
}
