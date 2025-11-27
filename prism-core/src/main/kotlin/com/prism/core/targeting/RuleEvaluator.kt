package com.prism.core.targeting

import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.expression.spel.support.MapAccessor

object RuleEvaluator {
    private val parser = SpelExpressionParser()

    fun evaluate(rule: TargetingRule, context: UserContext): Boolean {
        if (rule.condition.isBlank()) return true

        return try {
            val exp = parser.parseExpression(rule.condition)

            // StandardEvaluationContext already supports Map access via ReflectivePropertyAccessor
            // No need to explicitly add MapAccessor (which is deprecated)
            val rootContext = StandardEvaluationContext(context.attributes)
            rootContext.addPropertyAccessor(MapAccessor())

            exp.getValue(rootContext, Boolean::class.java) ?: false
        } catch (e: Exception) {
            // Log error or handle gracefully
            false
        }
    }
}
