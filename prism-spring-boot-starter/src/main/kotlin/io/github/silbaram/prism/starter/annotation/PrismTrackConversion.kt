package io.github.silbaram.prism.starter.annotation

/**
 * 메소드 실행 후 자동으로 전환 이벤트를 추적하는 어노테이션입니다.
 *
 * Spring AOP를 통해 메소드 실행이 완료된 후 Prism 서버에 전환 이벤트를 기록합니다.
 * PrismContext.wasActuallyAssigned()를 체크하여 실제로 할당받은 경우에만 전환을 추적하므로
 * 통계 오염을 방지할 수 있습니다.
 *
 * **여러 이벤트 동시 추적:**
 * 이 어노테이션은 반복 가능(Repeatable)하므로 하나의 메서드에 여러 번 사용할 수 있습니다.
 *
 * 사용 예시:
 * ```kotlin
 * @PrismExperiment(experimentKey = "checkout-flow")
 * fun showCheckoutPage(@PrismUserId userId: String) {
 *     // 체크아웃 페이지 표시
 * }
 *
 * // 기본 사용: 항상 추적
 * @PrismTrackConversion(
 *     experimentKey = "checkout-flow",
 *     eventName = "purchase"
 * )
 * fun completePurchase(@PrismUserId userId: String, amount: Int): Boolean {
 *     // 결제 로직
 *     return true
 * }
 *
 * // 여러 이벤트 동시 추적
 * @PrismTrackConversion(experimentKey = "signup", eventName = "page_viewed")
 * @PrismTrackConversion(experimentKey = "signup", eventName = "form_started")
 * fun showSignupForm(@PrismUserId userId: String) {
 *     // page_viewed와 form_started 두 이벤트 모두 기록됨
 * }
 *
 * // 조건부 다중 이벤트
 * @PrismTrackConversion(experimentKey = "signup", eventName = "submitted")
 * @PrismTrackConversion(
 *     experimentKey = "signup",
 *     eventName = "success",
 *     trackWhen = TrackCondition.RETURN_TRUE
 * )
 * fun submitSignup(@PrismUserId userId: String): Boolean {
 *     // submitted는 항상 기록, success는 true 반환 시에만 기록
 *     return registerUser()
 * }
 * ```
 *
 * **주의사항:**
 * - 이 어노테이션은 @PrismExperiment와 함께 사용하거나, PrismContext에 할당 정보가 있어야 합니다.
 * - 할당받지 않은 상태에서는 전환이 자동으로 스킵됩니다.
 *
 * @property experimentKey Prism 실험의 고유 키
 * @property eventName 추적할 전환 이벤트 이름 (예: "purchase", "signup", "click")
 * @property userIdParam userId를 추출할 파라미터 이름 (기본값: "userId")
 * @property trackOnException 예외 발생 시에도 전환을 추적할지 여부 (기본값: false)
 * @property trackWhen 전환 추적 조건 (기본값: TrackCondition.ALWAYS)
 */
@JvmRepeatable(PrismTrackConversions::class)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PrismTrackConversion(
    val experimentKey: String,
    val eventName: String,
    val userIdParam: String = "userId",
    val trackOnException: Boolean = false,
    val trackWhen: TrackCondition = TrackCondition.ALWAYS
)
