
package com.prism.core.splitter

import com.prism.core.model.Experiment
import com.prism.core.model.Variant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class TrafficSplitterTest {

    @Test
    fun `should always assign same variant for same user`() {
        val variants = listOf(
            Variant("A", 50),
            Variant("B", 50)
        )
        val experiment = Experiment("test-exp", variants)
        
        val user1 = "user-123"
        val result1 = TrafficSplitter.assign(experiment, user1)
        val result2 = TrafficSplitter.assign(experiment, user1)
        
        assertEquals(result1, result2, "Assignment should be deterministic")
    }

    @Test
    fun `should distribute traffic according to weights`() {
        val variants = listOf(
            Variant("A", 30),
            Variant("B", 70)
        )
        val experiment = Experiment("distribution-test", variants)
        
        val totalUsers = 10000
        val results = mutableMapOf<String, Int>()
        
        for (i in 1..totalUsers) {
            val userId = "user-$i"
            val variant = TrafficSplitter.assign(experiment, userId)
            if (variant != null) {
                results[variant.name] = results.getOrDefault(variant.name, 0) + 1
            }
        }
        
        val countA = results["A"] ?: 0
        val countB = results["B"] ?: 0
        
        val ratioA = countA.toDouble() / totalUsers
        val ratioB = countB.toDouble() / totalUsers
        
        println("A: $countA ($ratioA), B: $countB ($ratioB)")
        
        // Allow 2% margin of error
        assertTrue(abs(ratioA - 0.3) < 0.02, "A ratio should be close to 0.3")
        assertTrue(abs(ratioB - 0.7) < 0.02, "B ratio should be close to 0.7")
    }
}
