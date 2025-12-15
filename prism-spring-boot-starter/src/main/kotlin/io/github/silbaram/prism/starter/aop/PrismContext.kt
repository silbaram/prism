package io.github.silbaram.prism.starter.aop

/**
 * 현재 실행 중인 실험의 variant 정보를 ThreadLocal로 관리하는 컨텍스트입니다.
 *
 * @PrismExperiment 어노테이션이 붙은 메소드 내부에서
 * 현재 할당된 variant를 조회할 수 있습니다.
 *
 * 사용 예시:
 * ```kotlin
 * @PrismExperiment(experimentKey = "discount_logic")
 * fun calculateDiscount(@PrismUserId userId: String, amount: Int): Int {
 *     val variant = PrismContext.getCurrentVariant()
 *     return when (variant) {
 *         "A" -> amount * 0.9  // 10% 할인
 *         "B" -> amount * 0.8  // 20% 할인
 *         else -> amount       // variant null 또는 기타 값
 *     }
 * }
 * ```
 */
object PrismContext {
    private val variantHolder = ThreadLocal<String?>()
    private val assignmentSuccessHolder = ThreadLocal<Boolean>()

    /**
     * 현재 스레드의 variant와 할당 성공 여부를 설정합니다.
     * (내부용 - PrismExperimentAspect에서 사용)
     *
     * @param variant 할당된 variant (실제 할당, 혹은 실패 시 null)
     * @param wasActualAssignment 실제로 서버에서 할당받았는지 여부
     *        - true: Prism API에서 정상적으로 할당받음
     *        - false: API 실패 또는 실험 미등록
     */
    internal fun setCurrentVariant(variant: String?, wasActualAssignment: Boolean) {
        variantHolder.set(variant)
        assignmentSuccessHolder.set(wasActualAssignment)
    }

    /**
     * 현재 스레드의 variant를 가져옵니다.
     *
     * @return 현재 할당된 variant, 없으면 null
     */
    fun getCurrentVariant(): String? {
        return variantHolder.get()
    }

    /**
     * 현재 사용자가 실제로 Prism API에서 variant를 할당받았는지 확인합니다.
     *
     * **중요:** 이 값이 false인 경우 통계 오염 방지를 위해 trackConversion을 호출하지 않아야 합니다.
     *
     * **사용 시나리오:**
     * - true: 정상적으로 할당받음 → trackConversion 호출 가능
     * - false: API 실패/미등록 → trackConversion 호출하면 안 됨
     *
     * **통계 오염 문제:**
     * API 실패 시 직접 trackConversion을 호출하면 impression 로그 없이 conversion만 쌓여
     * 통계가 왜곡될 수 있습니다.
     *
     * @return true = 실제 할당 성공, false = 할당 실패
     */
    fun wasActuallyAssigned(): Boolean {
        return assignmentSuccessHolder.get() ?: false
    }

    /**
     * 현재 스레드의 variant를 제거합니다.
     * (내부용 - PrismExperimentAspect에서 사용)
     */
    internal fun clear() {
        variantHolder.remove()
        assignmentSuccessHolder.remove()
    }
}
