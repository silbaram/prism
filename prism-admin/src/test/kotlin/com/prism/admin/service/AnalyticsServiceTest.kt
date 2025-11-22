
package com.prism.admin.service

import com.prism.admin.repository.AnalyticsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class AnalyticsServiceTest {

    private val analyticsRepository = mock(AnalyticsRepository::class.java)
    private val analyticsService = AnalyticsService(analyticsRepository)

    @Test
    fun `should calculate CVR and determine winner`() {
        val experimentKey = "test-exp"
        
        // Mock impressions: A=100, B=100
        `when`(analyticsRepository.countImpressionsByVariant(experimentKey)).thenReturn(listOf(
            arrayOf("A", 100L),
            arrayOf("B", 100L)
        ))
        
        // Mock conversions: A=10, B=20
        `when`(analyticsRepository.countConversionsByVariant(experimentKey)).thenReturn(listOf(
            arrayOf("A", 10L),
            arrayOf("B", 20L)
        ))

        val result = analyticsService.getExperimentStats(experimentKey)

        assertEquals(2, result.stats.size)
        
        val statA = result.stats.find { it.variant == "A" }!!
        assertEquals(10.0, statA.cvr)
        
        val statB = result.stats.find { it.variant == "B" }!!
        assertEquals(20.0, statB.cvr)
        
        assertEquals("B", result.winnerVariant)
    }
}
