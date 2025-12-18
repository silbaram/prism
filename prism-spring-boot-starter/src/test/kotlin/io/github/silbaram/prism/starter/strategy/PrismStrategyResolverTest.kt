package io.github.silbaram.prism.starter.strategy

import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismStrategy
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationContext

class PrismStrategyResolverTest {

    private lateinit var mockApplicationContext: ApplicationContext
    private lateinit var mockPrismClient: PrismClient
    private lateinit var prismExperimentClient: PrismExperimentClient
    private lateinit var resolver: PrismStrategyResolver

    @BeforeEach
    fun setUp() {
        mockApplicationContext = mockk()
        mockPrismClient = mockk(relaxed = true)
        prismExperimentClient = PrismExperimentClient(mockPrismClient)
        resolver = PrismStrategyResolver(mockApplicationContext, prismExperimentClient)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `variant A에 맞는 전략이 반환된다`() {
        // Given: variant A가 할당됨
        every { mockPrismClient.assign("user-123", "pricing_strategy") } returns AssignmentResponse(
            userId = "user-123",
            experimentKey = "pricing_strategy",
            variant = "A",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        // ApplicationContext에 전략 Bean들 등록
        val strategyA = TestPricingStrategyA()
        val strategyB = TestPricingStrategyB()
        every { mockApplicationContext.getBeansOfType(TestPricingStrategy::class.java) } returns mapOf(
            "strategyA" to strategyA,
            "strategyB" to strategyB
        )

        // When: 전략 선택
        val strategy = resolver.resolve<TestPricingStrategy>("user-123", "pricing_strategy")

        // Then: variant A의 전략이 반환됨
        val result = strategy.calculatePrice(1000)
        assertEquals(900, result)  // 10% 할인
    }

    @Test
    fun `variant B에 맞는 전략이 반환된다`() {
        // Given: variant B가 할당됨
        every { mockPrismClient.assign("user-456", "pricing_strategy") } returns AssignmentResponse(
            userId = "user-456",
            experimentKey = "pricing_strategy",
            variant = "B",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        val strategyA = TestPricingStrategyA()
        val strategyB = TestPricingStrategyB()
        every { mockApplicationContext.getBeansOfType(TestPricingStrategy::class.java) } returns mapOf(
            "strategyA" to strategyA,
            "strategyB" to strategyB
        )

        // When: 전략 선택
        val strategy = resolver.resolve<TestPricingStrategy>("user-456", "pricing_strategy")

        // Then: variant B의 전략이 반환됨
        val result = strategy.calculatePrice(1000)
        assertEquals(800, result)  // 20% 할인
    }

    @Test
    fun `variant가 null이면 예외가 발생한다`() {
        // Given: variant가 할당되지 않음 (control 전략도 없음)
        every { mockPrismClient.assign("user-789", "pricing_strategy") } returns AssignmentResponse(
            userId = "user-789",
            experimentKey = "pricing_strategy",
            variant = null,
            resultCode = ResponseCode.EXPERIMENT_NOT_FOUND.code,
            resultMessage = "Experiment not found"
        )

        val strategyA = TestPricingStrategyA()
        every { mockApplicationContext.getBeansOfType(TestPricingStrategy::class.java) } returns mapOf(
            "strategyA" to strategyA
        )

        // When & Then: 예외 발생
        val exception = assertThrows<IllegalStateException> {
            resolver.resolve<TestPricingStrategy>("user-789", "pricing_strategy")
        }
        assertTrue(exception.message!!.contains("variant='control'"))
    }

    // 테스트용 인터페이스 및 구현체
    interface TestPricingStrategy {
        fun calculatePrice(amount: Int): Int
    }

    @PrismStrategy(variant = "A", experimentKey = "pricing_strategy")
    class TestPricingStrategyA : TestPricingStrategy {
        override fun calculatePrice(amount: Int): Int = (amount * 0.9).toInt()  // 10% 할인
    }

    @PrismStrategy(variant = "B", experimentKey = "pricing_strategy")
    class TestPricingStrategyB : TestPricingStrategy {
        override fun calculatePrice(amount: Int): Int = (amount * 0.8).toInt()  // 20% 할인
    }

    interface TestCheckoutStrategy {
        fun processCheckout(amount: Int): Int
    }

    @PrismStrategy(variant = "premium", experimentKey = "checkout_strategy")
    class TestCheckoutStrategyPremium : TestCheckoutStrategy {
        override fun processCheckout(amount: Int): Int = (amount * 0.95).toInt()  // 5% 할인
    }

    @PrismStrategy(variant = "control", experimentKey = "checkout_strategy")
    class TestCheckoutStrategyControl : TestCheckoutStrategy {
        override fun processCheckout(amount: Int): Int = amount  // 할인 없음
    }
}
