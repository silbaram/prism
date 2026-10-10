package io.github.silbaram.prism.core.targeting

/** Implementations must be thread safe and reject rules they cannot evaluate. */
interface TargetingEvaluator {
    /** Validate without evaluating user data or executing a rule. */
    fun validateSyntax(condition: String)
    fun evaluate(rule: TargetingRule, context: UserContext): Boolean
}

/** Optional SPI for existing, unversioned SpEL expressions. Only one provider may be installed. */
interface LegacyTargetingEvaluator : TargetingEvaluator

class UnsupportedTargetingRuleException(message: String) : IllegalArgumentException(message)
