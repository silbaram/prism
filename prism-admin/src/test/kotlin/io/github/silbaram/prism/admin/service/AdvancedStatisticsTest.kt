package io.github.silbaram.prism.admin.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class AdvancedStatisticsTest {
    private fun sample(n: Int, successes: Int) = AnalysisMoments().apply { repeat(n) { add(it < successes, null) } }
    @Test fun `confidence sequences cover empty groups and shrink while correcting all looks and arms`() {
        assertEquals(AnalysisInterval(0.0, 1.0), sequentialInterval(0, 0, 2))
        val interval = sequentialInterval(50, 100, 2)
        val radius = sqrt(ln(2.0 * 2 * 100 * 101 / 0.05) / 200)
        assertEquals(0.5 - radius, interval.lower, 1e-12)
        assertTrue(sequentialInterval(500, 1000, 2).lower > interval.lower)
        assertTrue(sequentialInterval(50, 100, 8).lower < interval.lower)
        assertTrue(sequentialDifference(sample(10000, 1000), sample(10000, 9000), 2).lower > 0)
        assertThrows(IllegalArgumentException::class.java) { sequentialInterval(2, 1, 2) }
    }
    @Test fun `beta binomial posterior is reproducible symmetric under equal data and responds to strong effects`() {
        val equal = bayesianEffect(sample(100, 50), sample(100, 50))!!
        assertEquals(0.5, equal.probabilityBetter, 0.012)
        assertEquals(0.0, equal.meanDifference, 1e-12)
        assertTrue(equal.interval.lower < 0 && equal.interval.upper > 0)
        assertEquals(equal, bayesianEffect(sample(100, 50), sample(100, 50)))
        val strong = bayesianEffect(sample(1000, 100), sample(1000, 900))!!
        assertTrue(strong.probabilityBetter > 0.999)
        assertTrue(strong.interval.lower > 0)
        assertTrue(strong.expectedLoss < 1e-6)
        assertNotNull(bayesianEffect(sample(30, 0), sample(30, 30)))
        assertNull(bayesianEffect(sample(0, 0), sample(30, 1)))
    }
    @Test fun `CUPED removes preperiod imbalance and remains stable under large baseline offsets`() {
        fun group(successes: Int, offset: Double) = AnalysisMoments().apply {
            repeat(100) { add(it < successes, offset + if (it < successes) 1.0 else 0.0) }
        }
        val effect = cupedEffect(group(40, 1e8), group(60, 1e8))
        assertTrue(effect.available)
        assertEquals(1.0, effect.theta!!, 1e-6)
        assertEquals(0.2, effect.rawDifference!!, 1e-12)
        assertEquals(0.0, effect.adjustedDifference!!, 1e-6)
        assertTrue(effect.adjustedStandardError!! < effect.rawStandardError!!)
        assertTrue(effect.varianceReduction!! > 0.99)
    }
    @Test fun `CUPED does not turn missing preperiod values into zero or claim reduction with constant data`() {
        val missing = sample(30, 15)
        val constant = AnalysisMoments().apply { repeat(30) { add(it < 15, 1.0) } }
        assertFalse(cupedEffect(missing, constant).available)
        assertFalse(cupedEffect(constant, constant).available)
        val small = AnalysisMoments().apply { repeat(10) { add(it < 5, it.toDouble()) } }
        assertFalse(cupedEffect(small, small).available)
    }
}
