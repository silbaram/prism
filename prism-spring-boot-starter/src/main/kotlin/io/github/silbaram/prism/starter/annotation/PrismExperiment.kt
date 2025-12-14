package io.github.silbaram.prism.starter.annotation

/**
 * 메소드에 A/B 테스트 실험을 적용하는 어노테이션입니다.
 *
 * Spring AOP를 통해 메소드 실행 전에 Prism 서버에 할당된 variant를 조회하고,
 * variant에 따라 다른 로직을 실행할 수 있습니다.
 *
 * 사용 예시:
 * ```kotlin
 * @PrismExperiment(experimentKey = "discount_logic")
 * fun calculateDiscount(@PrismUserId userId: String, amount: Int): Int {
 *     // variant에 따라 다른 할인 로직 실행
 *     return amount
 * }
 * ```
 *
 * @property experimentKey Prism 실험의 고유 키
 * @property userIdParam userId를 추출할 파라미터 이름 (기본값: "userId")
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PrismExperiment(
    val experimentKey: String,
    val userIdParam: String = "userId"
)
