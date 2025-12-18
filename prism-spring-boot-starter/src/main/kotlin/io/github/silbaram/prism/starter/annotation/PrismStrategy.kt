package io.github.silbaram.prism.starter.annotation

import org.springframework.stereotype.Component

/**
 * 특정 variant에 대한 전략 구현체임을 나타내는 어노테이션입니다.
 *
 * 공통 인터페이스를 구현한 여러 클래스에 이 어노테이션을 붙이면,
 * PrismStrategyResolver가 현재 할당된 variant에 맞는 구현체를 자동으로 선택합니다.
 *
 * 사용 예시:
 * ```kotlin
 * // 공통 인터페이스
 * interface CheckoutStrategy {
 *     fun calculatePrice(amount: Int): Int
 * }
 *
 * // variant A 구현체
 * @PrismStrategy(variant = "A", experimentKey = "checkout_discount")
 * class CheckoutStrategyA : CheckoutStrategy {
 *     override fun calculatePrice(amount: Int): Int = (amount * 0.9).toInt()  // 10% 할인
 * }
 *
 * // variant B 구현체
 * @PrismStrategy(variant = "B", experimentKey = "checkout_discount")
 * class CheckoutStrategyB : CheckoutStrategy {
 *     override fun calculatePrice(amount: Int): Int = (amount * 0.8).toInt()  // 20% 할인
 * }
 *
 * // control 구현체
 * @PrismStrategy(variant = "control", experimentKey = "checkout_discount")
 * class CheckoutStrategyControl : CheckoutStrategy {
 *     override fun calculatePrice(amount: Int): Int = amount  // 할인 없음
 * }
 *
 * // 사용
 * @Service
 * class CheckoutService(
 *     private val strategyResolver: PrismStrategyResolver,
 *     private val conversionTracker: PrismConversionTracker
 * ) {
 *     fun processCheckout(userId: String, amount: Int): Int {
 *         val strategy = strategyResolver.resolve<CheckoutStrategy>(userId, "checkout_discount")
 *         val result = strategy.calculatePrice(amount)
 *         // 필요한 경우 명시적으로 전환 추적
 *         if (result > 0) {
 *             conversionTracker.trackConversionSafe(userId, "checkout_discount", "purchase")
 *         }
 *         return result
 *     }
 * }
 * ```
 *
 * @property variant 이 전략이 적용될 variant (예: "A", "B", "control")
 * @property experimentKey Prism 실험의 고유 키
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class PrismStrategy(
    val variant: String,
    val experimentKey: String
)
