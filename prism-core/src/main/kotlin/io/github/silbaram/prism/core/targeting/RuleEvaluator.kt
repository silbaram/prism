package io.github.silbaram.prism.core.targeting

import java.util.ServiceLoader

/** Routes explicitly versioned data rules and optional legacy expressions without Spring. */
object RuleEvaluator : TargetingEvaluator {
    private val legacy by lazy {
        ServiceLoader.load(LegacyTargetingEvaluator::class.java, LegacyTargetingEvaluator::class.java.classLoader)
            .toList().also { require(it.size <= 1) { "Only one legacy targeting provider may be installed" } }
            .singleOrNull()
    }

    private fun evaluator(condition: String): TargetingEvaluator = when {
        condition.startsWith(JsonTargetingEvaluator.PREFIX) -> JsonTargetingEvaluator
        condition.startsWith("prism:") -> throw UnsupportedTargetingRuleException("Unsupported targeting rule version")
        else -> legacy ?: throw UnsupportedTargetingRuleException("Legacy rules require prism-targeting-spel or a custom evaluator")
    }

    override fun validateSyntax(condition: String) {
        require(condition.length <= 10_000) { "타겟팅 규칙은 10,000자 이하여야 합니다." }
        if (condition.isNotBlank()) evaluator(condition).validateSyntax(condition)
    }

    override fun evaluate(rule: TargetingRule, context: UserContext): Boolean {
        require(rule.condition.length <= 10_000) { "타겟팅 규칙은 10,000자 이하여야 합니다." }
        if (rule.condition.isBlank()) return true
        return evaluator(rule.condition).evaluate(rule, context)
    }
}
