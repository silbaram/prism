package com.prism.admin.service

import io.github.silbaram.prism.admin.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReliabilityStatisticsTest {
    private val weights = listOf("A" to 50, "B" to 50)
    private fun srm(a: Long, b: Long) = sampleRatioMismatch(weights, mapOf("A" to a, "B" to b), a + b)
    private fun group(name: String, users: Long, converted: Long?) = VariantStats(name, users, converted, null, null)

    @Test
    fun `SRM uses chi square tails for two and multiple unequal weight variants`() {
        assertEquals(SrmStatus.PASS, srm(100, 100).status)
        assertEquals(1.0, srm(100, 100).pValue)
        assertEquals(0.317310507862914, srm(55, 45).pValue!!, 1e-12)
        val unequal = sampleRatioMismatch(listOf("A" to 20, "B" to 30, "C" to 50),
            mapOf("A" to 30L, "B" to 30L, "C" to 40L), 100)
        assertEquals(7.0, unequal.statistic!!, 1e-12)
        assertEquals(2, unequal.degreesOfFreedom)
        assertEquals(0.0301973834223185, unequal.pValue!!, 1e-12)
        assertEquals(SrmStatus.MISMATCH, srm(700, 300).status)
        assertEquals(SrmStatus.MISMATCH, srm(1000000, 0).status)
        assertEquals(0.0, srm(1000000, 0).pValue)
    }

    @Test
    fun `insufficient samples invalid weights unknown variants and crossover never pass`() {
        assertEquals(SrmStatus.INSUFFICIENT_DATA, srm(0, 0).status)
        assertEquals(SrmStatus.INSUFFICIENT_DATA, srm(4, 5).status)
        assertEquals(SrmStatus.PASS, srm(5, 5).status)
        assertEquals(SrmStatus.INSUFFICIENT_DATA,
            sampleRatioMismatch(listOf("A" to 100, "B" to 0), mapOf("A" to 100L), 100).status)
        listOf(listOf("A" to 40, "B" to 40), listOf("A" to -1, "B" to 101), listOf("A" to 50, "A" to 50))
            .forEach { assertEquals(SrmStatus.INVALID_DATA, sampleRatioMismatch(it, emptyMap(), 0).status) }
        assertEquals(SrmStatus.INVALID_DATA,
            sampleRatioMismatch(weights, mapOf("A" to 100L, "B" to 100L), 199).status)
        assertEquals(SrmStatus.INVALID_DATA,
            sampleRatioMismatch(weights, mapOf("A" to 100L, "removed" to 1L), 101).status)
        assertEquals(SrmStatus.INVALID_DATA,
            sampleRatioMismatch(listOf("A" to 100, "B" to 0), mapOf("A" to 100L, "B" to 1L), 101).status)
    }

    @Test
    fun `single omnibus conversion test matches known two and three group values`() {
        val two = compareConversionRates(listOf(group("A", 100, 10), group("B", 100, 30)), srm(100, 100), true)
        assertEquals(ComparisonStatus.AVAILABLE, two.status)
        assertEquals(12.5, two.statistic!!, 1e-12)
        assertEquals(0.000406952017444959, two.pValue!!, 1e-12)
        val three = compareConversionRates(listOf(group("A", 100, 10), group("B", 100, 20), group("C", 100, 30)),
            SrmResult(SrmStatus.PASS, ""), true)
        assertEquals(2, three.degreesOfFreedom)
        assertEquals(0.00193045413622771, three.pValue!!, 1e-12)
        val equal = compareConversionRates(listOf(group("A", 100, 10), group("B", 100, 10)), srm(100, 100), true)
        assertEquals(1.0, equal.pValue!!, 1e-12)
    }

    @Test
    fun `an unobserved positive weight group cannot be silently dropped from the omnibus test`() {
        val srm = sampleRatioMismatch(listOf("A" to 49, "B" to 50, "C" to 1),
            mapOf("A" to 495L, "B" to 505L), 1000)
        assertEquals(SrmStatus.PASS, srm.status)
        val result = compareConversionRates(listOf(group("A", 495, 50), group("B", 505, 100), group("C", 0, 0)), srm, true)
        assertEquals(ComparisonStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.pValue)
    }

    @Test
    fun `invalid historical variant identities cannot receive a passing reliability result`() {
        assertEquals(SrmStatus.INVALID_DATA,
            sampleRatioMismatch(listOf(" " to 50, "B" to 50), mapOf(" " to 100L, "B" to 100L), 200).status)
    }

    @Test
    fun `comparison is suppressed until ended and for invalid sparse or SRM failing data`() {
        val groups = listOf(group("A", 100, 10), group("B", 100, 20))
        assertEquals(ComparisonStatus.NOT_ENDED, compareConversionRates(groups, srm(100, 100), false).status)
        assertEquals(ComparisonStatus.BLOCKED, compareConversionRates(groups, srm(700, 300), true).status)
        assertEquals(ComparisonStatus.BLOCKED,
            compareConversionRates(listOf(group("A", 100, null), group("B", 100, null)), srm(100, 100), true).status)
        listOf(0L, 1L, 100L).forEach { converted ->
            assertEquals(ComparisonStatus.INSUFFICIENT_DATA,
                compareConversionRates(listOf(group("A", 100, converted), group("B", 100, converted)), srm(100, 100), true).status)
        }
    }
}
