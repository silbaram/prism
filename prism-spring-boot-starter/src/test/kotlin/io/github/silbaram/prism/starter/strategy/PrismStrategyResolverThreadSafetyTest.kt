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
import org.springframework.context.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * PrismStrategyResolver의 Thread-Safety 문제를 재현하는 테스트
 */
class PrismStrategyResolverThreadSafetyTest {

    private lateinit var mockApplicationContext: ApplicationContext
    private lateinit var mockPrismClient: PrismClient
    private lateinit var prismExperimentClient: PrismExperimentClient
    private lateinit var resolver: PrismStrategyResolver

    // scanStrategiesForExperiment() 호출 횟수 추적
    private val scanCallCount = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        mockApplicationContext = mockk()
        mockPrismClient = mockk(relaxed = true)
        prismExperimentClient = PrismExperimentClient(mockPrismClient)
        resolver = PrismStrategyResolver(mockApplicationContext, prismExperimentClient)
        scanCallCount.set(0)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `Issue 1 재현 - getOrPut 동시 호출 시 scanStrategies가 여러 번 실행될 수 있다`() {
        // Given: variant A 할당
        every { mockPrismClient.assign(any(), "race_test") } returns AssignmentResponse(
            userId = "user",
            experimentKey = "race_test",
            variant = "A",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        val strategyA = TestRaceStrategy()

        // getBeansOfType이 호출될 때마다 카운트 증가
        every { mockApplicationContext.getBeansOfType(TestStrategy::class.java) } answers {
            scanCallCount.incrementAndGet()
            Thread.sleep(50)  // 스캔이 느리다고 가정 (race condition 유도)
            mapOf("strategyA" to strategyA)
        }

        // When: 100개 스레드가 동시에 같은 전략 요청 (캐시 미스)
        val threadCount = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val results = mutableListOf<TestStrategy>()

        repeat(threadCount) {
            executor.submit {
                try {
                    val strategy = resolver.resolve<TestStrategy>("user-$it", "race_test")
                    synchronized(results) {
                        results.add(strategy)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        // Then: 모든 스레드가 전략을 받음
        assertEquals(threadCount, results.size)

        // 문제: scanStrategies가 여러 번 호출될 수 있음
        val actualScanCount = scanCallCount.get()
        println("🔍 scanStrategiesForExperiment() 호출 횟수: $actualScanCount")
        println("⚠️  예상: 1번, 실제: ${actualScanCount}번")

        // 이상적으로는 1번만 호출되어야 하지만, getOrPut의 race condition으로 여러 번 호출됨
        if (actualScanCount > 1) {
            println("❌ Thread-Safety 문제 재현됨: 중복 스캔 발생")
        }
    }

    // 테스트용 인터페이스 및 구현체
    interface TestStrategy {
        fun execute(): String
    }

    @PrismStrategy(variant = "A", experimentKey = "race_test")
    class TestRaceStrategy : TestStrategy {
        override fun execute(): String = "A"
    }
}
