package com.prism.admin.service



import io.github.silbaram.prism.infrastructure.persistence.repository.ImpressionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.repository.ConversionLogRepository
import io.github.silbaram.prism.admin.service.AnalyticsService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/**
 * AnalyticsService 단위 테스트
 *
 * 이 테스트 클래스는 AnalyticsService의 핵심 비즈니스 로직을 검증합니다.
 * 주요 테스트 항목:
 * 1. 실험 결과 집계 및 통계 계산:
 *    - ImpressionLogRepository와 ConversionLogRepository의 Mock을 사용하여 노출 및 전환 데이터를 시뮬레이션합니다.
 *    - 각 변형(Variant)별 전환율(CVR)이 정확히 계산되는지 확인합니다.
 *    - 가장 높은 CVR을 가진 변형이 승자(winnerVariant)로 선정되는지 검증합니다.
 */
class AnalyticsServiceTest : FunSpec({

    val impressionRepository = mockk<ImpressionLogRepository>()
    val conversionRepository = mockk<ConversionLogRepository>()
    val analyticsService = AnalyticsService(impressionRepository, conversionRepository)

    test("CVR을 계산하고 승자를 도출한다") {
        val experimentKey = "test-exp"

        every { impressionRepository.countImpressionsByVariant(experimentKey) } returns listOf(
            arrayOf("A", 100L),
            arrayOf("B", 100L)
        )

        every { conversionRepository.countConversionsByVariant(experimentKey) } returns listOf(
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
