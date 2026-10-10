package io.github.silbaram.prism.core.targeting

import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.core.splitter.TrafficSplitter
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonTargetingEvaluatorTest {
    private fun rule(json: String) = TargetingRule(JsonTargetingEvaluator.PREFIX + json)
    private fun matches(json: String, attributes: Map<String, Any>) = RuleEvaluator.evaluate(rule(json), UserContext(attributes))

    @Test fun `portable compound rule targets eligible users without a legacy provider`() {
        val json = """{"all":[{"attribute":"age","op":"gte","value":20},{"attribute":"country","op":"eq","value":"KR"}]}"""
        assertTrue(matches(json, mapOf("age" to 25, "country" to "KR")))
        assertFalse(matches(json, mapOf("age" to 19, "country" to "KR")))
        assertFalse(matches(json, mapOf("age" to 25, "country" to "US")))
        assertFalse(matches(json, emptyMap()))
        val experiment = Experiment("checkout", listOf(Variant("A", 100)), listOf(rule(json)))
        assertEquals("A", TrafficSplitter.assign(experiment, "u", UserContext(mapOf("age" to 25, "country" to "KR")))?.name)
        assertNull(TrafficSplitter.assign(experiment, "u", UserContext(mapOf("age" to 10, "country" to "KR"))))
    }

    @Test fun `numeric comparisons retain decimal and large integer precision`() {
        assertTrue(matches("""{"attribute":"n","op":"eq","value":9007199254740993}""", mapOf("n" to 9007199254740993L)))
        assertFalse(matches("""{"attribute":"n","op":"eq","value":9007199254740993}""", mapOf("n" to 9007199254740992L)))
        assertTrue(matches("""{"attribute":"n","op":"eq","value":1.0000000000000001}""", mapOf("n" to BigDecimal("1.0000000000000001"))))
        assertFalse(matches("""{"attribute":"n","op":"eq","value":1.0000000000000001}""", mapOf("n" to 1.0)))
        assertTrue(matches("""{"attribute":"n","op":"eq","value":1.0}""", mapOf("n" to 1)))
    }

    @Test fun `missing and incompatible values do not pass positive comparisons`() {
        for (op in listOf("eq", "ne", "gt", "gte", "lt", "lte")) {
            val json = """{"attribute":"age","op":"$op","value":20}"""
            for (attributes in listOf(emptyMap(), mapOf("age" to "20"), mapOf("age" to Double.NaN), mapOf("age" to Double.POSITIVE_INFINITY)))
                assertFalse(matches(json, attributes), "$op with $attributes")
        }
        assertFalse(matches("""{"attribute":"active","op":"eq","value":true}""", mapOf("active" to "true")))
    }

    @Test fun `all comparison operators have explicit boundary behavior`() {
        for (op in listOf("eq", "ne", "gt", "gte", "lt", "lte")) {
            val expected = when (op) { "eq", "gte", "lte" -> true; else -> false }
            assertEquals(expected, matches("""{"attribute":"n","op":"$op","value":20}""", mapOf("n" to 20)))
        }
        assertTrue(matches("""{"attribute":"n","op":"gt","value":20}""", mapOf("n" to 21)))
        assertTrue(matches("""{"attribute":"n","op":"lt","value":20}""", mapOf("n" to 19)))
    }

    @Test fun `groups membership and presence use data only`() {
        val json = """{"all":[{"attribute":"country","op":"in","value":["KR","JP"]},{"any":[{"attribute":"premium","op":"eq","value":true},{"not":{"attribute":"blocked","op":"exists"}}]}]}"""
        assertTrue(matches(json, mapOf("country" to "KR")))
        assertFalse(matches(json, mapOf("country" to "KR", "blocked" to true)))
        assertTrue(matches(json, mapOf("country" to "JP", "blocked" to true, "premium" to true)))
        assertFalse(matches(json, mapOf("country" to "US", "premium" to true)))
    }

    @Test fun `syntax rejects unknown fields duplicate keys trailing data and executable operations`() {
        val invalid = listOf(
            """{"attribute":"age","op":"eq","value":20,"method":"run"}""",
            """{"attribute":"age","attribute":"country","op":"eq","value":20}""",
            """{"attribute":"age","op":"eq","value":20} true""",
            """{"attribute":"country","op":"matches","value":".*"}""",
            """{"attribute":"country","op":"gt","value":"KR"}""",
            """{"attribute":"age","op":"eq","value":null}""",
            """{"all":[]}""", """{"any":[]}""", """{"not":null}""",
            """{"all":[{"attribute":"age","op":"eq","value":20},{"method":"execute"}]}"""
        )
        for (json in invalid) assertThrows(IllegalArgumentException::class.java) { RuleEvaluator.validateSyntax(rule(json).condition) }
    }

    @Test fun `syntax complexity is bounded`() {
        var json = """{"attribute":"age","op":"eq","value":20}"""
        repeat(17) { json = """{"not":$json}""" }
        assertThrows(IllegalArgumentException::class.java) { RuleEvaluator.validateSyntax(rule(json).condition) }
        assertThrows(IllegalArgumentException::class.java) { RuleEvaluator.validateSyntax(JsonTargetingEvaluator.PREFIX + " ".repeat(10_001)) }
        assertThrows(IllegalArgumentException::class.java) { JsonTargetingEvaluator.validateSyntax(" ".repeat(10_001)) }
    }

    @Test fun `legacy expressions and unknown versions fail explicitly without an adapter`() {
        assertThrows(UnsupportedTargetingRuleException::class.java) { RuleEvaluator.validateSyntax("age >= 20") }
        assertThrows(UnsupportedTargetingRuleException::class.java) { RuleEvaluator.evaluate(TargetingRule("true"), UserContext(emptyMap())) }
        assertThrows(UnsupportedTargetingRuleException::class.java) { RuleEvaluator.validateSyntax("prism:v2:{}") }
        RuleEvaluator.validateSyntax("")
        assertTrue(RuleEvaluator.evaluate(TargetingRule(""), UserContext(emptyMap())))
    }

    @Test fun `an explicitly injected evaluator is used by the splitter`() {
        val evaluator = object : TargetingEvaluator {
            override fun validateSyntax(condition: String) { require(condition == "custom") }
            override fun evaluate(rule: TargetingRule, context: UserContext) = context.attributes["allowed"] == true
        }
        val experiment = Experiment("custom", listOf(Variant("A", 100)), listOf(TargetingRule("custom")))
        assertEquals("A", TrafficSplitter.assign(experiment, "u", UserContext(mapOf("allowed" to true)), targetingEvaluator = evaluator)?.name)
        assertNull(TrafficSplitter.assign(experiment, "u", targetingEvaluator = evaluator))
    }
}
