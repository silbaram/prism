package io.github.silbaram.prism.starter.annotation

/**
 * 메소드 파라미터에서 userId를 명시적으로 표시하는 어노테이션입니다.
 *
 * @PrismExperiment와 함께 사용되며, 이 어노테이션이 붙은 파라미터의 값을
 * userId로 사용하여 Prism 서버에 variant를 요청합니다.
 *
 * 사용 예시:
 * ```kotlin
 * @PrismExperiment(experimentKey = "discount_logic")
 * fun calculateDiscount(@PrismUserId userId: String, amount: Int): Int {
 *     return amount
 * }
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PrismUserId
