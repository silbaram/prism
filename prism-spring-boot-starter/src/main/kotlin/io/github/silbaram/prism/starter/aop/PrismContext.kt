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
 *         else -> amount
 *     }
 * }
 * ```
 */
object PrismContext {
    private val variantHolder = ThreadLocal<String>()

    /**
     * 현재 스레드의 variant를 설정합니다.
     * (내부용 - PrismExperimentAspect에서 사용)
     */
    internal fun setCurrentVariant(variant: String) {
        variantHolder.set(variant)
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
     * 현재 스레드의 variant를 제거합니다.
     * (내부용 - PrismExperimentAspect에서 사용)
     */
    internal fun clear() {
        variantHolder.remove()
    }
}
