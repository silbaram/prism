package com.prism.core.targeting

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleEvaluatorTest {

    @Test
    fun `should evaluate simple condition`() {
        val rule = TargetingRule("age >= 20")
        val context = UserContext.from(mapOf("age" to 25))
        
        assertTrue(RuleEvaluator.evaluate(rule, context))
    }

    @Test
    fun `should evaluate string comparison`() {
        val rule = TargetingRule("os == 'iOS'")
        val context = UserContext.from(mapOf("os" to "iOS"))
        
        assertTrue(RuleEvaluator.evaluate(rule, context))
    }

    @Test
    fun `should return false when condition not met`() {
        val rule = TargetingRule("level == 'VIP'")
        val context = UserContext.from(mapOf("level" to "BASIC"))
        
        assertFalse(RuleEvaluator.evaluate(rule, context))
    }
    
    @Test
    fun `should handle missing attributes gracefully (or as false)`() {
        val rule = TargetingRule("age > 10")
        val context = UserContext.from(emptyMap())
        
        // SpEL throws exception if property not found in map context usually, 
        // RuleEvaluator catches and returns false
        assertFalse(RuleEvaluator.evaluate(rule, context))
    }
}
