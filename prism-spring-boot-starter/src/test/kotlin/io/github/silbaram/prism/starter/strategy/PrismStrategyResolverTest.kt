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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun `Fallback 테스트 - variant 전략이 없으면 control로 폴백한다`() {
        // Given: variant "C"가 할당됐지만 해당 전략이 없음
        every { mockPrismClient.assign("user-789", "pricing_strategy") } returns AssignmentResponse(
            userId = "user-789",
            experimentKey = "pricing_strategy",
            variant = "C",  // 존재하지 않는 variant
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        val strategyA = TestPricingStrategyA()
        val strategyControl = TestPricingStrategyControl()
        every { mockApplicationContext.getBeansOfType(TestPricingStrategy::class.java) } returns mapOf(
            "strategyA" to strategyA,
            "strategyControl" to strategyControl
        )

        // When: 전략 선택
        val strategy = resolver.resolve<TestPricingStrategy>("user-789", "pricing_strategy")

        // Then: control 전략이 반환됨
        val result = strategy.calculatePrice(1000)
        assertEquals(1000, result, "variant C 전략이 없으므로 control로 폴백해야 함")
    }

    @Test
    fun `Fallback 테스트 - variant와 control 모두 없으면 예외가 발생한다`() {
        // Given: variant "D"가 할당되고, control 전략도 없음
        every { mockPrismClient.assign("user-999", "pricing_strategy") } returns AssignmentResponse(
            userId = "user-999",
            experimentKey = "pricing_strategy",
            variant = "D",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        val strategyA = TestPricingStrategyA()
        every { mockApplicationContext.getBeansOfType(TestPricingStrategy::class.java) } returns mapOf(
            "strategyA" to strategyA
            // control 전략 없음!
        )

        // When & Then: 예외 발생
        val exception = assertThrows<IllegalStateException> {
            resolver.resolve<TestPricingStrategy>("user-999", "pricing_strategy")
        }
        assertTrue(exception.message!!.contains("variant='D'"))
        assertTrue(exception.message!!.contains("'control'"))
    }

    @Test
    fun `Fallback 테스트 - control variant가 할당되면 폴백 없이 바로 실행된다`() {
        // Given: control이 할당됨
        every { mockPrismClient.assign("user-control", "pricing_strategy") } returns AssignmentResponse(
            userId = "user-control",
            experimentKey = "pricing_strategy",
            variant = "control",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        val strategyControl = TestPricingStrategyControl()
        every { mockApplicationContext.getBeansOfType(TestPricingStrategy::class.java) } returns mapOf(
            "strategyControl" to strategyControl
        )

        // When: 전략 선택
        val strategy = resolver.resolve<TestPricingStrategy>("user-control", "pricing_strategy")

        // Then: control 전략이 실행됨
        val result = strategy.calculatePrice(1000)
        assertEquals(1000, result)
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

    @PrismStrategy(variant = "control", experimentKey = "pricing_strategy")
    class TestPricingStrategyControl : TestPricingStrategy {
        override fun calculatePrice(amount: Int): Int = amount  // 할인 없음
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

    @Test
    fun `동시성 테스트 - 여러 스레드가 동시에 전략을 resolve해도 안전하다`() {
        // Given: variant A, B를 번갈아 할당
        every { mockPrismClient.assign(any(), "pricing_strategy") } answers {
            val userId = firstArg<String>()
            val userNumber = userId.substringAfter("user-").toInt()
            val variant = if (userNumber % 2 == 0) "A" else "B"
            AssignmentResponse(
                userId = userId,
                experimentKey = "pricing_strategy",
                variant = variant,
                resultCode = ResponseCode.SUCCESS.code,
                resultMessage = "Success"
            )
        }

        val strategyA = TestPricingStrategyA()
        val strategyB = TestPricingStrategyB()
        every { mockApplicationContext.getBeansOfType(TestPricingStrategy::class.java) } returns mapOf(
            "strategyA" to strategyA,
            "strategyB" to strategyB
        )

        // 100개의 스레드로 동시에 1000번씩 호출
        val threadCount = 100
        val iterationsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = ConcurrentHashMap.newKeySet<Throwable>()
        val successCount = AtomicInteger(0)

        // When: 여러 스레드가 동시에 전략 resolve
        repeat(threadCount) { threadIndex ->
            executor.submit {
                try {
                    repeat(iterationsPerThread) { iteration ->
                        val userId = "user-${threadIndex * iterationsPerThread + iteration}"
                        val strategy = resolver.resolve<TestPricingStrategy>(userId, "pricing_strategy")
                        val result = strategy.calculatePrice(1000)

                        // 결과 검증
                        val userNumber = userId.substringAfter("user-").toInt()
                        val expected = if (userNumber % 2 == 0) 900 else 800
                        assertEquals(expected, result, "userId=$userId, result mismatch")
                        successCount.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        // Then: 모든 스레드가 완료될 때까지 대기 (최대 30초)
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test timeout after 30 seconds")
        executor.shutdown()

        // 에러가 없어야 함
        if (errors.isNotEmpty()) {
            errors.forEach { it.printStackTrace() }
            fail<Unit>("Concurrency test failed with ${errors.size} errors: ${errors.first().message}")
        }

        // 모든 요청이 성공해야 함
        val expectedTotal = threadCount * iterationsPerThread
        assertEquals(expectedTotal, successCount.get(), "성공한 요청 수가 예상과 다름")

        println("✅ 동시성 테스트 성공: ${successCount.get()}개의 요청이 모두 정상 처리됨")
    }

    @Test
    fun `동시성 테스트 - 캐시 미스가 동시에 발생해도 안전하다`() {
        // Given: checkout_strategy 실험에 대한 설정 (기존 실험 재사용)
        every { mockPrismClient.assign(any(), "checkout_strategy") } answers {
            AssignmentResponse(
                userId = firstArg(),  // 실제 userId를 반환
                experimentKey = "checkout_strategy",
                variant = "premium",
                resultCode = ResponseCode.SUCCESS.code,
                resultMessage = "Success"
            )
        }

        // 전략들 설정
        val premiumStrategy = TestCheckoutStrategyPremium()
        every { mockApplicationContext.getBeansOfType(TestCheckoutStrategy::class.java) } returns mapOf(
            "premium" to premiumStrategy
        )

        // 10개의 스레드가 동시에 첫 호출 (캐시 미스)
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val results = ConcurrentHashMap<Int, Int>()

        // When: 여러 스레드가 동시에 첫 호출 (캐시 미스)
        repeat(threadCount) { threadIndex ->
            executor.submit {
                try {
                    val strategy = resolver.resolve<TestCheckoutStrategy>("user-$threadIndex", "checkout_strategy")
                    val result = strategy.processCheckout(1000)
                    results[threadIndex] = result
                } finally {
                    latch.countDown()
                }
            }
        }

        // Then: 모든 스레드가 완료될 때까지 대기
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // 모든 결과가 올바르게 반환되어야 함
        assertEquals(threadCount, results.size)
        results.values.forEach { result ->
            assertEquals(950, result, "Result mismatch")  // premium: 5% 할인 = 950
        }

        println("✅ 캐시 미스 동시성 테스트 성공: ${results.size}개의 요청이 모두 정상 처리됨")
    }
}
