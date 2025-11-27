package com.prism.admin.service



import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.repository.ExperimentRepository
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.admin.api.service.ExperimentService
import io.github.silbaram.prism.admin.api.service.VariantDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import io.mockk.slot

/**
 * ExperimentService 단위 테스트
 *
 * 이 테스트 클래스는 실험 생성 및 관리 로직을 검증합니다.
 * 주요 테스트 항목:
 * 1. 실험 생성 성공 케이스:
 *    - 정상적인 가중치(합 100)를 가진 변형 목록으로 실험 생성 시, 상태가 DRAFT이고 변형이 올바르게 저장되는지 확인합니다.
 *    - ExperimentRepository의 save 메서드가 호출되는지 검증합니다.
 * 2. 유효성 검사 실패 케이스:
 *    - 변형 가중치의 합이 100이 아닐 경우 IllegalArgumentException이 발생하는지 확인합니다.
 *    - 예외 발생 시 저장 로직이 실행되지 않음을 검증합니다.
 */
class ExperimentServiceTest : FunSpec({

    val experimentRepository = mockk<ExperimentRepository>(relaxed = true)
    val experimentService = ExperimentService(experimentRepository)

    beforeTest {
        clearMocks(experimentRepository)
    }

    test("새 실험을 만들고 변형을 저장한다") {
        val variants = listOf(VariantDto("A", 50), VariantDto("B", 50))

        every { experimentRepository.findByKey("test-exp") } returns null
        val savedEntity = slot<ExperimentEntity>()
        every { experimentRepository.save(capture(savedEntity)) } answers { savedEntity.captured }

        val created = experimentService.createExperiment("test-exp", "Test Description", variants)

        created.key shouldBe "test-exp"
        created.status shouldBe ExperimentStatus.DRAFT
        created.variants.shouldHaveSize(2)
        created.variants.map { it.name }.shouldContainExactly("A", "B")
    }

    test("가중치 합이 100이 아니면 예외를 던진다") {
        every { experimentRepository.findByKey("invalid-weight") } returns null

        shouldThrow<IllegalArgumentException> {
            experimentService.createExperiment(
                key = "invalid-weight",
                description = "Desc",
                variants = listOf(VariantDto("A", 30), VariantDto("B", 30))
            )
        }

        verify(exactly = 0) { experimentRepository.save(any()) }
    }
})
