package io.github.silbaram.prism.starter.annotation

/**
 * 전환 추적 조건을 정의하는 enum입니다.
 *
 * 메서드의 반환값을 기반으로 전환 이벤트를 추적할지 결정합니다.
 * 실패/null 결과를 기록할 때는 성공 목표와 별도의 보조 이벤트 이름을 사용하세요.
 * CVR에는 실험의 goalEventName과 일치하는 이벤트만 포함됩니다.
 */
enum class TrackCondition {
    /**
     * 항상 전환 추적 (기본값)
     *
     * 메서드가 정상적으로 완료되면 항상 전환을 추적합니다.
     */
    ALWAYS,

    /**
     * Boolean 반환값이 true일 때만 전환 추적
     *
     * 사용 예시: 결제 성공, 검증 통과 등
     *
     * **주의**: 반환 타입이 Boolean이 아니면 추적하지 않습니다.
     */
    RETURN_TRUE,

    /**
     * Boolean 반환값이 false일 때만 전환 추적
     *
     * 사용 예시: 결제 실패, 검증 실패 등
     *
     * **주의**: 반환 타입이 Boolean이 아니면 추적하지 않습니다.
     */
    RETURN_FALSE,

    /**
     * 반환값이 null이 아닐 때만 전환 추적
     *
     * 사용 예시: 상품 추천 성공, 검색 결과 존재 등
     */
    NOT_NULL,

    /**
     * 반환값이 null일 때만 전환 추적
     *
     * 사용 예시: 추천 실패, 검색 결과 없음 등
     */
    IS_NULL
}
