package io.github.silbaram.prism.core.targeting

/**
 * 타겟팅 규칙 평가를 위한 사용자 컨텍스트.
 *
 * 이 클래스는 사용자의 속성들을 담고 있으며, 타겟팅 규칙 평가 시 사용됩니다.
 * SpEL 표현식에서 이 속성들을 참조하여 조건을 평가할 수 있습니다.
 *
 * ## 사용 예시
 * ```kotlin
 * // 사용자 속성 설정
 * val context = UserContext(mapOf(
 *     "age" to 25,
 *     "country" to "KR",
 *     "isPremium" to true,
 *     "plan" to "enterprise"
 * ))
 *
 * // 또는 companion 메서드 사용
 * val context = UserContext.from(mapOf(
 *     "userId" to "user-123",
 *     "email" to "user@example.com"
 * ))
 *
 * // 타겟팅 규칙 평가
 * val rule = TargetingRule("age >= 18 && country == 'KR'")
 * val isTargeted = RuleEvaluator.evaluate(rule, context)
 * ```
 *
 * @property attributes 사용자의 속성들을 담은 Map (키: 속성명, 값: 속성값)
 * @see RuleEvaluator
 * @see TargetingRule
 */
data class UserContext(
    val attributes: Map<String, Any>
) {
    companion object {
        /**
         * Map으로부터 UserContext를 생성합니다.
         *
         * @param map 사용자 속성 맵
         * @return 생성된 UserContext
         */
        fun from(map: Map<String, Any>) = UserContext(map)
    }
}
