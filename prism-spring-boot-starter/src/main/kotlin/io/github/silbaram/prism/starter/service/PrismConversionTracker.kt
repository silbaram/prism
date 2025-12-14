package io.github.silbaram.prism.starter.service

import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.starter.aop.PrismContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 안전한 전환 추적을 제공하는 래퍼 컴포넌트입니다.
 *
 * **통계 오염 방지:**
 * 이 컴포넌트는 실제로 Prism API에서 variant를 할당받은 경우에만
 * 전환 이벤트를 추적합니다. API 장애/미등록 등으로 할당이 실패하면
 * 전환을 스킵하여 통계 왜곡을 방지합니다.
 *
 * **사용 예시:**
 * ```kotlin
 * @Service
 * class CheckoutService(
 *     private val conversionTracker: PrismConversionTracker
 * ) {
 *     @PrismExperiment(experimentKey = "checkout-flow")
 *     fun completePurchase(@PrismUserId userId: String, amount: Int) {
 *         // 구매 로직...
 *
 *         // 안전한 전환 추적 (자동으로 wasActuallyAssigned 체크)
 *         conversionTracker.trackConversionSafe(userId, "checkout-flow", "purchase")
 *     }
 * }
 * ```
 *
 * **왜 필요한가?**
 * ```
 * API 정상 시:
 *   - impression: variant=A/B/... 기록됨
 *   - conversion: variant=A/B/... 기록됨
 *   - ✅ 통계 정확
 *
 * API 장애 시 (이 래퍼 없이 직접 trackConversion 호출):
 *   - impression: 기록 안 됨 (API 실패)
 *   - conversion: 기록됨
 *   - ❌ 통계 오염! (impression 0인데 conversion만 증가)
 *
 * API 장애 시 (이 래퍼 사용):
 *   - impression: 기록 안 됨
 *   - conversion: 기록 안 됨 (스킵됨)
 *   - ✅ 통계 정확 유지
 * ```
 */
@Component
class PrismConversionTracker(
    private val prismClient: PrismClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 안전하게 전환 이벤트를 추적합니다.
     *
     * **동작 방식:**
     * 1. PrismContext.wasActuallyAssigned()를 확인
     * 2. 실제 할당받은 경우에만 trackConversion 호출
     * 3. 할당 실패 시 스킵 (로그만 기록)
     *
     * **주의:**
     * 이 메서드는 @PrismExperiment 어노테이션이 붙은 메서드 내부에서만 사용해야 합니다.
     * 그렇지 않으면 wasActuallyAssigned()가 항상 false를 반환합니다.
     *
     * @param userId 사용자 고유 식별자
     * @param experimentKey 실험 키
     * @param eventName 이벤트 이름 (예: "purchase", "signup", "click")
     */
    fun trackConversionSafe(userId: String, experimentKey: String, eventName: String) {
        if (PrismContext.wasActuallyAssigned()) {
            // 실제로 할당받은 경우 → 전환 추적
            prismClient.trackConversion(userId, experimentKey, eventName)
            logger.debug("전환 추적 성공: userId=$userId, experimentKey=$experimentKey, eventName=$eventName")
        } else {
            // 할당 실패 → 전환 추적 스킵
            logger.debug(
                "전환 추적 스킵 (할당 실패): " +
                "userId=$userId, experimentKey=$experimentKey, eventName=$eventName"
            )
        }
    }

    /**
     * 항상 전환 이벤트를 추적합니다 (할당 여부 무시).
     *
     * **경고:** 이 메서드는 통계 오염을 일으킬 수 있습니다!
     * 특별한 이유가 없다면 trackConversionSafe()를 사용하세요.
     *
     * **사용 케이스:**
     * - A/B 테스트와 무관한 일반 이벤트 추적
     * - 레거시 코드 호환성
     *
     * @param userId 사용자 고유 식별자
     * @param experimentKey 실험 키
     * @param eventName 이벤트 이름
     */
    fun trackConversionUnsafe(userId: String, experimentKey: String, eventName: String) {
        logger.warn(
            "Unsafe conversion tracking (통계 오염 가능): " +
            "userId=$userId, experimentKey=$experimentKey, eventName=$eventName"
        )
        prismClient.trackConversion(userId, experimentKey, eventName)
    }
}
