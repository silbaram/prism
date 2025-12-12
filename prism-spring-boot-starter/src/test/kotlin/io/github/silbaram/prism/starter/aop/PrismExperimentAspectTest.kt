package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismUserId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory

class PrismExperimentAspectTest {

    private lateinit var prismClient: PrismClient
    private lateinit var aspect: PrismExperimentAspect
    private lateinit var testService: TestService

    @BeforeEach
    fun setUp() {
        prismClient = mock(PrismClient::class.java)
        aspect = PrismExperimentAspect(prismClient)

        // AOP 프록시 설정
        val factory = AspectJProxyFactory(TestServiceImpl())
        factory.addAspect(aspect)
        testService = factory.getProxy()
    }

    @AfterEach
    fun tearDown() {
        PrismContext.clear()
    }

    @Test
    fun `@PrismUserId 어노테이션으로 userId 추출 테스트`() {
        // given
        val userId = "user123"
        val experimentKey = "discount_test"
        val expectedVariant = "B"

        `when`(prismClient.assign(userId, experimentKey))
            .thenReturn(AssignmentResponse(
                userId = userId,
                experimentKey = experimentKey,
                variant = expectedVariant,
                resultCode = "SUCCESS",
                resultMessage = "Assignment successful"
            ))

        // when
        val result = testService.calculateDiscountWithAnnotation(userId, 1000)

        // then
        verify(prismClient).assign(userId, experimentKey)
        assertNotNull(result)
        assertEquals(800, result) // B variant = 20% 할인
    }

    @Test
    fun `파라미터 이름으로 userId 추출 테스트`() {
        // given
        val userId = "user456"
        val experimentKey = "discount_test"
        val expectedVariant = "A"

        `when`(prismClient.assign(userId, experimentKey))
            .thenReturn(AssignmentResponse(
                userId = userId,
                experimentKey = experimentKey,
                variant = expectedVariant,
                resultCode = "SUCCESS",
                resultMessage = "Assignment successful"
            ))

        // when
        val result = testService.calculateDiscountWithParamName(userId, 1000)

        // then
        verify(prismClient).assign(userId, experimentKey)
        assertNotNull(result)
        assertEquals(900, result) // A variant = 10% 할인
    }

    @Test
    fun `Prism 서버 오류 시 defaultVariant 사용 테스트`() {
        // given
        val userId = "user789"
        val experimentKey = "discount_test"

        `when`(prismClient.assign(userId, experimentKey))
            .thenThrow(RuntimeException("서버 오류"))

        // when
        val result = testService.calculateDiscountWithAnnotation(userId, 1000)

        // then
        verify(prismClient).assign(userId, experimentKey)
        assertNotNull(result)
        assertEquals(900, result) // defaultVariant = "A" = 10% 할인
    }

    // 테스트용 서비스 인터페이스
    interface TestService {
        fun calculateDiscountWithAnnotation(userId: String, amount: Int): Int
        fun calculateDiscountWithParamName(userId: String, amount: Int): Int
    }

    // 테스트용 서비스 구현
    class TestServiceImpl : TestService {

        @PrismExperiment(experimentKey = "discount_test", defaultVariant = "A")
        override fun calculateDiscountWithAnnotation(
            @PrismUserId userId: String,
            amount: Int
        ): Int {
            val variant = PrismContext.getCurrentVariant()
            return when (variant) {
                "A" -> (amount * 0.9).toInt()  // 10% 할인
                "B" -> (amount * 0.8).toInt()  // 20% 할인
                else -> amount
            }
        }

        @PrismExperiment(experimentKey = "discount_test", defaultVariant = "A", userIdParam = "userId")
        override fun calculateDiscountWithParamName(userId: String, amount: Int): Int {
            val variant = PrismContext.getCurrentVariant()
            return when (variant) {
                "A" -> (amount * 0.9).toInt()  // 10% 할인
                "B" -> (amount * 0.8).toInt()  // 20% 할인
                else -> amount
            }
        }
    }
}
