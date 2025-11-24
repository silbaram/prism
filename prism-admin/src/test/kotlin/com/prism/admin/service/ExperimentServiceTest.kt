package com.prism.admin.service

// 실험 생성과 가중치 검증 로직을 MockK로 단위 테스트합니다.

import io.github.silbaram.prism.admin.api.domain.ExperimentStatus
import io.github.silbaram.prism.admin.api.repository.ExperimentRepository
import io.github.silbaram.prism.admin.api.domain.ExperimentEntity
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
