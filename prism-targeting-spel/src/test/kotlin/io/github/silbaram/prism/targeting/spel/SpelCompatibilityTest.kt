package io.github.silbaram.prism.targeting.spel

import io.github.silbaram.prism.core.targeting.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpelCompatibilityTest : FunSpec({
    test("SPI preserves unversioned comparisons and string methods") {
        val context = UserContext(mapOf("age" to 25, "country" to "KR"))
        RuleEvaluator.validateSyntax("age >= 20 && country.matches('[A-Z]{2}')")
        RuleEvaluator.evaluate(TargetingRule("age >= 20 && country.matches('[A-Z]{2}')"), context) shouldBe true
        RuleEvaluator.evaluate(TargetingRule("missing > 10"), context) shouldBe false
        RuleEvaluator.evaluate(TargetingRule("country.contains('U')"), context) shouldBe false
    }
    test("data rules do not get interpreted as SpEL when the adapter is present") {
        val rule = TargetingRule("""prism:v1:{"attribute":"country","op":"eq","value":"KR"}""")
        RuleEvaluator.evaluate(rule, UserContext(mapOf("country" to "KR"))) shouldBe true
        shouldThrow<UnsupportedTargetingRuleException> { RuleEvaluator.validateSyntax("prism:v2:{}") }
    }
    test("new format agrees with typed legacy rules on reference data") {
        val legacy = TargetingRule("age >= 20 && country == 'KR'")
        val portable = TargetingRule("""prism:v1:{"all":[{"attribute":"age","op":"gte","value":20},{"attribute":"country","op":"eq","value":"KR"}]}""")
        for (age in listOf(0, 19, 20, 25, 100)) for (country in listOf("KR", "JP", "US")) {
            val context = UserContext(mapOf("age" to age, "country" to country))
            RuleEvaluator.evaluate(portable, context) shouldBe RuleEvaluator.evaluate(legacy, context)
        }
    }
    test("malformed legacy expressions keep previous validation and evaluation behavior") {
        shouldThrow<IllegalArgumentException> { RuleEvaluator.validateSyntax("age >=") }
        RuleEvaluator.evaluate(TargetingRule("age >="), UserContext(emptyMap())) shouldBe false
    }
})
