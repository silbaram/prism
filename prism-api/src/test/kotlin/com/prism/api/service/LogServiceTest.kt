package com.prism.api.service

// 비동기 로그 적재 서비스가 노출·전환 이벤트를 정확히 저장하는지 검증하는 단위 테스트입니다.

import com.prism.api.repository.ConversionRepository
import com.prism.api.repository.ImpressionRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class LogServiceTest : FunSpec({

    val impressionRepository = mockk<ImpressionRepository>()
    val conversionRepository = mockk<ConversionRepository>()
    val logService = LogService(impressionRepository, conversionRepository)

    test("노출 로그를 비동기로 적재한다") {
        val impressionSlot = slot<com.prism.api.domain.ImpressionEntity>()
        every { impressionRepository.save(capture(impressionSlot)) } answers { impressionSlot.captured }

        logService.logImpression("test-exp", "A", "user-1")

        verify(exactly = 1) { impressionRepository.save(any()) }
        with(impressionSlot.captured) {
            experimentKey shouldBe "test-exp"
            variant shouldBe "A"
            userId shouldBe "user-1"
        }
    }

    test("전환 로그를 비동기로 적재한다") {
        val conversionSlot = slot<com.prism.api.domain.ConversionEntity>()
        every { conversionRepository.save(capture(conversionSlot)) } answers { conversionSlot.captured }

        logService.logConversion("test-exp", "user-1", "purchase")

        verify(exactly = 1) { conversionRepository.save(any()) }
        with(conversionSlot.captured) {
            experimentKey shouldBe "test-exp"
            userId shouldBe "user-1"
            eventName shouldBe "purchase"
        }
    }
})
