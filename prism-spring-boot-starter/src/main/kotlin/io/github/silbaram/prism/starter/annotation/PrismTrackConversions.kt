package io.github.silbaram.prism.starter.annotation

/**
 * @PrismTrackConversion 어노테이션을 여러 개 사용할 수 있도록 하는 컨테이너 어노테이션입니다.
 *
 * 이 어노테이션은 직접 사용하지 않습니다. @PrismTrackConversion을 여러 번 사용하면 자동으로 적용됩니다.
 * 여러 이벤트 중 실험의 goalEventName에 해당하는 이벤트만 CVR에 포함됩니다.
 *
 * 사용 예시:
 * ```kotlin
 * @PrismTrackConversion(experimentKey = "signup", eventName = "page_viewed")
 * @PrismTrackConversion(experimentKey = "signup", eventName = "form_started")
 * fun showSignupForm(@PrismUserId userId: String) {
 *     // 두 이벤트 모두 자동으로 추적됨
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PrismTrackConversions(
    val value: Array<PrismTrackConversion>
)
