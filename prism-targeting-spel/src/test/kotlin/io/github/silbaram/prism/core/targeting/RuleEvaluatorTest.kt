package io.github.silbaram.prism.core.targeting



import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

/**
 * RuleEvaluator 단위 테스트
 *
 * 이 테스트 클래스는 SpEL(Spring Expression Language) 기반의 타기팅 규칙 평가 로직을 검증합니다.
 * 주요 테스트 항목:
 * 1. 숫자 비교 규칙:
 *    - "age >= 20"과 같은 수치 비교 조건이 올바르게 평가되는지 확인합니다.
 * 2. 문자열 비교 규칙:
 *    - "os == 'iOS'"와 같은 문자열 일치 조건이 정확히 처리되는지 검증합니다.
 * 3. 조건 불일치 처리:
 *    - 사용자 컨텍스트가 규칙을 만족하지 않을 경우 false를 반환하는지 확인합니다.
 * 4. 예외 케이스 처리:
 *    - 규칙에서 참조하는 속성이 사용자 컨텍스트에 없을 경우 안전하게 false로 처리되는지 검증합니다.
 */
class RuleEvaluatorTest : FunSpec({

    test("사용자 나이가 조건을 만족하면 true를 반환한다") {
        val rule = TargetingRule("age >= 20")
        val context = UserContext.from(mapOf("age" to 25))

        RuleEvaluator.evaluate(rule, context).shouldBeTrue()
    }

    test("문자열 비교를 정확히 처리한다") {
        val rule = TargetingRule("os == 'iOS'")
        val context = UserContext.from(mapOf("os" to "iOS"))

        RuleEvaluator.evaluate(rule, context).shouldBeTrue()
    }

    test("조건을 만족하지 않으면 false를 반환한다") {
        val rule = TargetingRule("level == 'VIP'")
        val context = UserContext.from(mapOf("level" to "BASIC"))

        RuleEvaluator.evaluate(rule, context).shouldBeFalse()
    }

    test("존재하지 않는 속성은 false로 처리한다") {
        val rule = TargetingRule("age > 10")
        val context = UserContext.from(emptyMap())

        RuleEvaluator.evaluate(rule, context).shouldBeFalse()
    }
})
