package io.github.silbaram.prism.api.conversion.application.port.`in`

/**
 * 전환 추적 유스케이스 (Inbound Port).
 *
 * 헥사고날 아키텍처의 Inbound Port로, 사용자의 전환 이벤트를
 * 추적하고 기록하는 비즈니스 유스케이스를 정의합니다.
 *
 * 이 인터페이스는 비즈니스 로직의 진입점이며, 웹 컨트롤러나
 * 다른 어댑터에서 호출됩니다.
 */
interface TrackConversionUseCase {
    /**
     * 사용자의 전환 이벤트를 추적합니다.
     *
     * @param command 전환 추적 요청 커맨드
     * @return 추적 결과
     */
    fun trackConversion(command: TrackConversionCommand): TrackConversionResult
}

/**
 * 전환 추적 요청 커맨드.
 *
 * @property userId 사용자 고유 식별자
 * @property experimentKey 실험 키
 * @property eventName 전환 이벤트 이름
 */
data class TrackConversionCommand(
    val userId: String,
    val experimentKey: String,
    val eventName: String
)

/**
 * 전환 추적 결과.
 *
 * @property userId 사용자 고유 식별자
 * @property experimentKey 실험 키
 * @property eventName 전환 이벤트 이름
 * @property variantName 할당된 변형 이름 (노출 로그 없으면 null)
 * @property success 추적 성공 여부
 * @property message 결과 메시지
 */
data class TrackConversionResult(
    val userId: String,
    val experimentKey: String,
    val eventName: String,
    val variantName: String?,
    val success: Boolean,
    val message: String = ""
) {
    companion object {
        fun success(
            userId: String,
            experimentKey: String,
            eventName: String,
            variantName: String?
        ) = TrackConversionResult(
            userId = userId,
            experimentKey = experimentKey,
            eventName = eventName,
            variantName = variantName,
            success = true,
            message = "Conversion tracked successfully"
        )

        fun impressionNotFound(
            userId: String,
            experimentKey: String,
            eventName: String
        ) = TrackConversionResult(
            userId = userId,
            experimentKey = experimentKey,
            eventName = eventName,
            variantName = null,
            success = true,
            message = "Conversion tracked without impression (no variant)"
        )
    }
}
