package io.github.silbaram.prism.core.targeting

import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.expression.spel.support.MapAccessor

/**
 * 타겟팅 규칙 평가기.
 *
 * 이 객체는 Spring Expression Language (SpEL)를 사용하여
 * 타겟팅 규칙을 평가하고 사용자가 실험 대상인지 판별합니다.
 *
 * ## 작동 원리
 * 1. SpEL 표현식 파싱: 문자열 조건을 SpEL 표현식으로 변환
 * 2. 평가 컨텍스트 생성: 사용자 속성을 SpEL에서 접근 가능하도록 설정
 * 3. 표현식 평가: SpEL 엔진으로 조건 평가 후 Boolean 결과 반환
 *
 * ## 지원되는 SpEL 표현식
 * - 비교 연산자: `==`, `!=`, `>`, `<`, `>=`, `<=`
 * - 논리 연산자: `&&`, `||`, `!`
 * - 문자열 매칭: `.equals()`, `.contains()`, `.matches()`
 * - 컬렉션 연산: `in`, `contains`
 *
 * ## 사용 예시
 * ```kotlin
 * val context = UserContext(mapOf(
 *     "age" to 25,
 *     "country" to "KR",
 *     "isPremium" to true
 * ))
 *
 * // 단순 비교
 * val rule1 = TargetingRule("age >= 20")
 * RuleEvaluator.evaluate(rule1, context) // true
 *
 * // 복합 조건
 * val rule2 = TargetingRule("age >= 18 && country == 'KR'")
 * RuleEvaluator.evaluate(rule2, context) // true
 *
 * // 문자열 매칭
 * val rule3 = TargetingRule("country.matches('[A-Z]{2}')")
 * RuleEvaluator.evaluate(rule3, context) // true
 * ```
 *
 * ## 에러 처리
 * 표현식 평가 중 에러가 발생하면 `false`를 반환합니다.
 * 이는 안전한 실패(fail-safe) 전략으로, 잘못된 조건으로 인해
 * 전체 실험이 중단되는 것을 방지합니다.
 *
 * @see TargetingRule
 * @see UserContext
 */
object RuleEvaluator {
    /**
     * SpEL 표현식 파서.
     * 문자열 조건을 SpEL Expression 객체로 변환하는 데 사용됩니다.
     */
    private val parser = SpelExpressionParser()

    /**
     * 타겟팅 규칙을 평가하여 사용자가 조건을 만족하는지 확인합니다.
     *
     * ## 평가 과정
     * 1. 조건이 비어있으면 `true` 반환 (모든 사용자 대상)
     * 2. SpEL 표현식 파싱
     * 3. 사용자 컨텍스트를 SpEL 평가 컨텍스트로 변환
     * 4. 표현식 평가 후 Boolean 결과 반환
     *
     * ## 에러 처리
     * 다음과 같은 경우 `false`를 반환합니다:
     * - SpEL 파싱 실패 (잘못된 표현식 문법)
     * - 평가 중 예외 발생 (존재하지 않는 속성 참조 등)
     * - 평가 결과가 null인 경우
     *
     * 에러 발생 시 로그를 남겨 디버깅을 용이하게 합니다.
     *
     * @param rule 평가할 타겟팅 규칙
     * @param context 사용자의 속성 정보를 담은 컨텍스트
     * @return 조건 만족 여부 (true: 대상, false: 비대상 또는 에러)
     */
    fun evaluate(rule: TargetingRule, context: UserContext): Boolean {
        // 빈 조건은 모든 사용자를 대상으로 함
        if (rule.condition.isBlank()) {
            return true
        }

        return try {
            // 1단계: SpEL 표현식 파싱
            val expression = parser.parseExpression(rule.condition)

            // 2단계: SpEL 평가 컨텍스트 생성
            // StandardEvaluationContext는 Map의 키를 속성처럼 접근 가능하게 함
            val evaluationContext = createEvaluationContext(context)

            // 3단계: 표현식 평가
            // Boolean 타입으로 결과를 요청하며, null이면 false 반환
            expression.getValue(evaluationContext, Boolean::class.java) ?: false
        } catch (e: Exception) {
            // 에러 발생 시 false 반환 (안전한 실패)
            // 라이브러리 사용자(prism-api, prism-admin)가 필요 시 로깅 처리
            false
        }
    }

    /**
     * 사용자 컨텍스트를 SpEL 평가 컨텍스트로 변환합니다.
     *
     * MapAccessor를 추가하여 Map의 키를 SpEL 표현식에서
     * 속성처럼 접근할 수 있게 합니다.
     *
     * 예: `context.attributes["age"]` → SpEL에서 `age`로 접근 가능
     *
     * @param context 사용자 컨텍스트
     * @return SpEL 평가 컨텍스트
     */
    private fun createEvaluationContext(context: UserContext): StandardEvaluationContext {
        val evaluationContext = StandardEvaluationContext(context.attributes)

        // MapAccessor를 추가하여 Map의 키를 직접 속성처럼 접근 가능하게 함
        // 예: attributes["age"] → SpEL에서 age로 접근
        evaluationContext.addPropertyAccessor(MapAccessor())

        return evaluationContext
    }
}
