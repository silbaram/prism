package com.prism.admin.service

// AnalyticsService가 변형별 노출·전환 집계를 바탕으로 CVR과 승자를 산출하는지 확인하는 테스트입니다.

import io.github.silbaram.prism.admin.api.repository.AnalyticsRepository
import io.github.silbaram.prism.admin.api.service.AnalyticsService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class AnalyticsServiceTest : FunSpec({

    val analyticsRepository = mockk<AnalyticsRepository>()
    val analyticsService = AnalyticsService(analyticsRepository)

    test("CVR을 계산하고 승자를 도출한다") {
        val experimentKey = "test-exp"

        every { analyticsRepository.countImpressionsByVariant(experimentKey) } returns listOf(
            arrayOf("A", 100L),
            arrayOf("B", 100L)
        )

        every { analyticsRepository.countConversionsByVariant(experimentKey) } returns listOf(
            arrayOf("A", 10L),
            arrayOf("B", 20L)
        )

        val result = analyticsService.getExperimentStats(experimentKey)

        result.stats.size shouldBe 2
        with(result.stats.first { it.variant == "A" }) {
            cvr shouldBe 10.0
        }
        with(result.stats.first { it.variant == "B" }) {
            cvr shouldBe 20.0
        }
        result.winnerVariant shouldBe "B"
    }
})
