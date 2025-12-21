package io.github.silbaram.prism.starter.annotation

/**
 * 특정 variant에 대해 실행될 메서드를 지정하는 어노테이션입니다.
 *
 * 같은 클래스 내에서 experimentKey가 동일한 여러 메서드에 이 어노테이션을 붙이면,
 * AOP가 현재 할당된 variant에 맞는 메서드를 자동으로 선택해서 실행합니다.
 *
 * 사용 예시:
 * ```kotlin
 * @Service
 * class CheckoutService(
 *     private val variantRouter: PrismVariantMethodRouter
 * ) {
 *     fun processCheckout(userId: String, amount: Int): Int {
 *         val result = variantRouter.route(this, userId, "checkout_discount", amount)
 *         // 필요한 경우 명시적으로 전환 추적
 *         if (result > 0) {
 *             conversionTracker.trackConversionSafe(userId, "checkout_discount", "purchase")
 *         }
 *         return result
 *     }
 *
 *     @PrismVariantMethod(variant = "A", experimentKey = "checkout_discount")
 *     fun processCheckoutA(amount: Int): Int {
 *         return (amount * 0.9).toInt()  // 10% 할인
 *     }
 *
 *     @PrismVariantMethod(variant = "B", experimentKey = "checkout_discount")
 *     fun processCheckoutB(amount: Int): Int {
 *         return (amount * 0.8).toInt()  // 20% 할인
 *     }
 *
 *     @PrismVariantMethod(variant = "control", experimentKey = "checkout_discount")
 *     fun processCheckoutControl(amount: Int): Int {
 *         return amount  // 할인 없음
 *     }
 * }
 * ```
 *
 * @property variant 이 메서드가 실행될 variant (예: "A", "B", "control")
 * @property experimentKey Prism 실험의 고유 키
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PrismVariantMethod(
    val variant: String,
    val experimentKey: String
)
