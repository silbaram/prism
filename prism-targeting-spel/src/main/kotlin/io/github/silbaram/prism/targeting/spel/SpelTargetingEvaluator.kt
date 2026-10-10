package io.github.silbaram.prism.targeting.spel

import io.github.silbaram.prism.core.targeting.LegacyTargetingEvaluator
import io.github.silbaram.prism.core.targeting.TargetingRule
import io.github.silbaram.prism.core.targeting.UserContext
import org.springframework.expression.ParseException
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.MapAccessor
import org.springframework.expression.spel.support.StandardEvaluationContext

/** Compatibility for trusted administrator expressions; retains the existing SpEL semantics. */
class SpelTargetingEvaluator : LegacyTargetingEvaluator {
    private val parser = SpelExpressionParser()

    override fun validateSyntax(condition: String) {
        require(condition.length <= 10_000) { "타겟팅 규칙은 10,000자 이하여야 합니다." }
        try { if (condition.isNotBlank()) parser.parseExpression(condition) }
        catch (_: ParseException) { throw IllegalArgumentException("타겟팅 규칙 문법을 확인하세요.") }
    }

    override fun evaluate(rule: TargetingRule, context: UserContext): Boolean {
        if (rule.condition.isBlank()) return true
        return try {
            val evaluationContext = StandardEvaluationContext(context.attributes)
            evaluationContext.addPropertyAccessor(MapAccessor())
            parser.parseExpression(rule.condition).getValue(evaluationContext, Boolean::class.java) ?: false
        } catch (_: Exception) { false }
    }
}
