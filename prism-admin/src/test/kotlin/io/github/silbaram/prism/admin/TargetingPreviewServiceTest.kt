package io.github.silbaram.prism.admin

import io.github.silbaram.prism.admin.service.TargetingPreviewService
import io.github.silbaram.prism.core.targeting.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class TargetingPreviewServiceTest {
    private val preview = TargetingPreviewService()

    @Test fun `integer samples use JVM integer types without losing boundary values`() {
        val numbers = preview.attributes("""{"minInt":-2147483648,"maxInt":2147483647,"aboveInt":2147483648,"belowInt":-2147483649,"minLong":-9223372036854775808,"maxLong":9223372036854775807,"aboveLong":9223372036854775808,"belowLong":-9223372036854775809}""").attributes
        assertEquals(Int.MIN_VALUE, numbers["minInt"])
        assertEquals(Int.MAX_VALUE, numbers["maxInt"])
        assertEquals(2147483648L, numbers["aboveInt"])
        assertEquals(-2147483649L, numbers["belowInt"])
        assertEquals(Long.MIN_VALUE, numbers["minLong"])
        assertEquals(Long.MAX_VALUE, numbers["maxLong"])
        assertEquals(BigInteger("9223372036854775808"), numbers["aboveLong"])
        assertEquals(BigInteger("-9223372036854775809"), numbers["belowLong"])
    }

    @Test fun `SpEL integer arithmetic matches the contexts passed by JVM SDK callers`() {
        for ((value, condition) in listOf(29 to "age / 10 == 2", -29 to "age / 10 == -2", 2147483648L to "age / 10 == 214748364")) {
            val runtime = UserContext(mapOf("age" to value))
            val expected = RuleEvaluator.evaluate(TargetingRule(condition), runtime)
            assertTrue(expected)
            val result = preview.evaluate(listOf(condition), preview.attributes("""{"age":$value}"""))
            assertEquals(expected, result.matched, "Preview changed the arithmetic of $value")
        }
        assertTrue(preview.evaluate(listOf("age instanceof T(java.math.BigInteger)"),
            preview.attributes("""{"age":9223372036854775808}""")).matched)
    }

    @Test fun `long decimals and large integers remain exact for portable targeting rules`() {
        val decimal = "0.12345678901234567890123456789"
        val context = preview.attributes("""{"amount":$decimal,"id":9007199254740993}""")
        assertEquals(BigDecimal(decimal), context.attributes["amount"])
        assertEquals(9007199254740993L, context.attributes["id"])
        assertTrue(preview.evaluate(listOf("""prism:v1:{"all":[{"attribute":"amount","op":"eq","value":$decimal},{"attribute":"id","op":"eq","value":9007199254740993}]}"""), context).matched)
    }

    @Test fun `numeric range checks still reject oversized integers and extreme decimal scales`() {
        for (number in listOf("9".repeat(257), "1e-1025", "1e1025")) {
            assertThrows(IllegalArgumentException::class.java) { preview.attributes("""{"value":$number}""") }
        }
    }
}
