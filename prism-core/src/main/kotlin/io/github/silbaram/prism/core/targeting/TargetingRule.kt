package io.github.silbaram.prism.core.targeting

/**
 * A/B 테스트의 타겟팅 규칙.
 *
 * 이 클래스는 특정 사용자가 실험 대상인지 판별하는 조건을 정의합니다.
 * Spring Expression Language (SpEL)를 사용하여 유연한 조건 표현이 가능합니다.
 *
 * ## SpEL 조건 예시
 * ```kotlin
 * // 나이가 20세 이상인 사용자
 * TargetingRule("age >= 20")
 *
 * // 특정 국가의 사용자
 * TargetingRule("country == 'KR'")
 *
 * // 프리미엄 사용자이면서 활성 상태인 경우
 * TargetingRule("isPremium && isActive")
 *
 * // 복잡한 조건 조합
 * TargetingRule("age >= 18 && age <= 35 && country == 'US'")
 * ```
 *
 * @property condition SpEL 표현식으로 작성된 조건 문자열
 * @see RuleEvaluator
 * @see UserContext
 */
data class TargetingRule(
    val condition: String
)
