package io.github.silbaram.prism.core.targeting

/**
 * A/B 테스트의 타겟팅 규칙.
 *
 * 이 클래스는 특정 사용자가 실험 대상인지 판별하는 조건을 정의합니다.
 * 기본 형식은 `prism:v1:` 데이터 규칙이며 기존 SpEL은 선택적 호환 모듈이 필요합니다.
 *
 * ## 데이터 규칙 예시
 * ```kotlin
 * TargetingRule("""prism:v1:{"attribute":"age","op":"gte","value":20}""")
 * ```
 *
 * ## 호환 모듈을 사용하는 기존 SpEL 조건 예시
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
 * @property condition 버전이 명시된 데이터 규칙 또는 호환 모듈의 기존 표현식
 * @see RuleEvaluator
 * @see UserContext
 */
data class TargetingRule(
    val condition: String
)
