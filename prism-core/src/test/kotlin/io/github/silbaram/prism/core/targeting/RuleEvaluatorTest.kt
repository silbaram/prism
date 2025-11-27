package io.github.silbaram.prism.core.targeting

// RuleEvaluator의 SpEL 기반 타기팅 평가 로직을 검증하는 단위 테스트입니다.

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

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
