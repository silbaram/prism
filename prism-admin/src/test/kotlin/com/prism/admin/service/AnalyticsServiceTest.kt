package com.prism.admin.service

import io.github.silbaram.prism.admin.service.AnalyticsService
import io.github.silbaram.prism.admin.service.wilsonInterval
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.VariantEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import io.mockk.*

class AnalyticsServiceTest : FunSpec({
    val impressions = mockk<ImpressionLogRepository>()
    val conversions = mockk<ConversionLogRepository>()
    val experiments = mockk<ExperimentRepository>()
    val service = AnalyticsService(impressions, conversions, experiments)
    beforeTest { clearMocks(impressions, conversions, experiments) }

    test("only the configured goal determines CVR and every configured variant remains visible") {
        val experiment = ExperimentEntity(key = "e", description = "", goalEventName = "purchase")
        listOf("A", "B", "C").forEach { experiment.addVariant(VariantEntity(name = it, weight = 0)) }
        every { experiments.findByKey("e") } returns experiment
        every { impressions.countImpressionsByVariant("e") } returns listOf(arrayOf("A", 100L), arrayOf("B", 11L))
        every { conversions.countConversionsByVariant("e", "purchase") } returns listOf(arrayOf("A", 10L), arrayOf("B", 1L))
        val result = service.getExperimentStats("e")
        result.goalEventName shouldBe "purchase"
        result.stats.map { it.variant } shouldBe listOf("A", "B", "C")
        val a = result.stats.first()
        a.cvr shouldBe 10.0
        a.confidenceInterval!!.lower shouldBe (5.52291 plusOrMinus 0.00001)
        a.confidenceInterval!!.upper shouldBe (17.43657 plusOrMinus 0.00001)
        result.stats.last().cvr shouldBe null
        result.stats.last().confidenceInterval shouldBe null
    }

    test("legacy experiment without a goal shows no invented zero conversion rate") {
        every { experiments.findByKey("e") } returns ExperimentEntity(key = "e", description = "")
        every { impressions.countImpressionsByVariant("e") } returns listOf(arrayOf("A", 100L))
        val stat = service.getExperimentStats("e").stats.single()
        stat.conversions shouldBe null
        stat.cvr shouldBe null
        stat.confidenceInterval shouldBe null
        verify { conversions wasNot Called }
    }

    test("Wilson interval handles zero and all conversions without a winner threshold") {
        val zero = wilsonInterval(0, 10)
        zero.lower shouldBe (0.0 plusOrMinus 1e-12)
        zero.upper shouldBe (27.75328 plusOrMinus 0.00001)
        val all = wilsonInterval(10, 10)
        all.lower shouldBe (72.24672 plusOrMinus 0.00001)
        all.upper shouldBe (100.0 plusOrMinus 1e-12)
    }
})
