package io.github.silbaram.prism.core.targeting

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.BigInteger

/** Versioned data-only rules. No reflection, JVM types, method calls, or implicit type coercion. */
object JsonTargetingEvaluator : TargetingEvaluator {
    const val PREFIX = "prism:v1:"
    private val mapper = ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxNumberLength(256).build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    private class Predicate(val matches: (UserContext) -> Boolean)
    private class Budget(var nodes: Int = 0)
    private val cache = object : LinkedHashMap<String, Predicate>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Predicate>?) = size > 256
    }

    override fun validateSyntax(condition: String) {
        require(condition.length <= 10_000) { "Targeting rule exceeds 10,000 characters" }
        if (condition.isNotBlank()) compile(condition)
    }
    override fun evaluate(rule: TargetingRule, context: UserContext): Boolean {
        require(rule.condition.length <= 10_000) { "Targeting rule exceeds 10,000 characters" }
        return rule.condition.isBlank() || compile(rule.condition).matches(context)
    }

    @Synchronized
    private fun compile(condition: String): Predicate {
        require(condition.length <= 10_000) { "Targeting rule exceeds 10,000 characters" }
        if (!condition.startsWith(PREFIX)) throw UnsupportedTargetingRuleException("Expected prism:v1 data rule")
        cache[condition]?.let { return it }
        val predicate = try {
            parse(mapper.readTree(condition.substring(PREFIX.length)), 0, Budget())
        } catch (exception: IllegalArgumentException) { throw exception }
        catch (_: Exception) { throw IllegalArgumentException("Invalid prism:v1 targeting rule") }
        cache[condition] = predicate
        return predicate
    }

    private fun parse(node: JsonNode?, depth: Int, budget: Budget): Predicate {
        require(depth <= 16 && ++budget.nodes <= 256) { "Targeting rule is too complex" }
        require(node != null && node.isObject) { "Targeting rule must be an object" }
        val fields = node.fieldNames().asSequence().toSet()
        for (group in listOf("all", "any", "not")) {
            if (group !in fields) continue
            require(fields == setOf(group)) { "Group cannot contain additional fields" }
            if (group == "not") {
                val child = parse(node[group], depth + 1, budget)
                return Predicate { !child.matches(it) }
            }
            val children = node[group]
            require(children.isArray && children.size() in 1..100) { "Group requires 1–100 rules" }
            // Compile every branch before evaluation, including short-circuited branches.
            val rules = children.map { parse(it, depth + 1, budget) }
            return if (group == "all") Predicate { context -> rules.all { it.matches(context) } }
                else Predicate { context -> rules.any { it.matches(context) } }
        }
        require(node["attribute"]?.isTextual == true && node["op"]?.isTextual == true) { "Rule requires attribute and op" }
        val attribute = node["attribute"].textValue()
        val operation = node["op"].textValue()
        require(attribute.isNotBlank() && attribute.length <= 255) { "Invalid attribute name" }
        if (operation == "exists") {
            require(fields == setOf("attribute", "op")) { "exists has no value" }
            return Predicate { it.attributes[attribute] != null }
        }
        require(fields == setOf("attribute", "op", "value")) { "Rule requires exactly attribute, op and value" }
        if (operation == "in") {
            val values = node["value"]
            require(values.isArray && values.size() in 1..100) { "in requires 1–100 scalar values" }
            val literals = values.map(::literal)
            return Predicate { context -> literals.any { compare(context.attributes[attribute], it) == 0 } }
        }
        require(operation in setOf("eq", "ne", "gt", "gte", "lt", "lte")) { "Unsupported targeting operator" }
        val value = literal(node["value"])
        require(operation in setOf("eq", "ne") || value is BigDecimal) { "Ordered comparison requires a number" }
        return Predicate { context ->
            val comparison = compare(context.attributes[attribute], value)
            comparison != null && when (operation) {
                "eq" -> comparison == 0
                "ne" -> comparison != 0
                "gt" -> comparison > 0
                "gte" -> comparison >= 0
                "lt" -> comparison < 0
                else -> comparison <= 0
            }
        }
    }

    private fun literal(node: JsonNode): Any = when {
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node.isNumber -> requireNotNull(number(node.asText())) { "Invalid finite numeric literal" }
        else -> throw IllegalArgumentException("Only string, boolean and numeric literals are supported")
    }

    private fun compare(actual: Any?, expected: Any): Int? = when (expected) {
        is BigDecimal -> when (actual) {
            is Byte, is Short, is Int, is Long, is Float, is Double, is BigDecimal, is BigInteger ->
                number(actual.toString())?.compareTo(expected)
            else -> null
        }
        is String -> (actual as? String)?.compareTo(expected)
        is Boolean -> (actual as? Boolean)?.compareTo(expected)
        else -> null
    }

    private fun number(text: String): BigDecimal? = text.takeIf { it.length <= 256 }?.toBigDecimalOrNull()
        ?.takeIf { it.precision() <= 256 && it.scale() in -1024..1024 }
}
