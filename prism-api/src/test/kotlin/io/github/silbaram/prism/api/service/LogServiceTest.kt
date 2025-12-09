package io.github.silbaram.prism.api.service



import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ConversionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ImpressionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ConversionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ImpressionLogRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

/**
 * LogService 단위 테스트
 *
 * 이 테스트 클래스는 사용자 행동 로그(노출, 전환)의 비동기 적재 로직을 검증합니다.
 * 주요 테스트 항목:
 * 1. 노출 로그 적재:
 *    - logImpression 메서드 호출 시 ImpressionLogRepository를 통해 데이터가 저장되는지 확인합니다.
 *    - 저장된 ImpressionLogEntity의 필드 값(실험 키, 변형, 사용자 ID)이 정확한지 검증합니다.
 * 2. 전환 로그 적재:
 *    - logConversion 메서드 호출 시 ConversionLogRepository를 통해 데이터가 저장되는지 확인합니다.
 *    - 저장된 ConversionLogEntity의 필드 값(실험 키, 사용자 ID, 이벤트명)이 정확한지 검증합니다.
 */
class LogServiceTest : FunSpec({

    val impressionRepository = mockk<ImpressionLogRepository>()
    val conversionRepository = mockk<ConversionLogRepository>()
    val logService = LogService(impressionRepository, conversionRepository)

    test("노출 로그를 비동기로 적재한다") {
        val impressionSlot = slot<ImpressionLogEntity>()
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
        val conversionSlot = slot<ConversionLogEntity>()
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
