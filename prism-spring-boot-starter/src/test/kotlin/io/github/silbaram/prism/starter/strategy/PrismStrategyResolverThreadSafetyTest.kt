package io.github.silbaram.prism.starter.strategy

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismStrategy
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PrismStrategyResolverThreadSafetyTest {
    @Test
    fun `concurrent resolution scans the strategy beans exactly once`() {
        val context = mockk<ApplicationContext>()
        val transport = mockk<PrismClient>()
        every { transport.assign(any(), "race_test") } answers {
            AssignmentResponse(firstArg(), "race_test", "A", "0000", "Success")
        }
        val strategy = TestRaceStrategy()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { context.getBeansOfType(TestStrategy::class.java) } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            mapOf("strategyA" to strategy)
        }
        val resolver = PrismStrategyResolver(context, PrismExperimentClient(transport))
        val executor = Executors.newFixedThreadPool(16)
        try {
            val results = (1..100).map { user ->
                executor.submit<TestStrategy> { resolver.resolve<TestStrategy>("user-$user", "race_test") }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            results.forEach { assertSame(strategy, it.get(5, TimeUnit.SECONDS)) }
            verify(exactly = 1) { context.getBeansOfType(TestStrategy::class.java) }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    interface TestStrategy { fun execute(): String }
    @PrismStrategy(variant = "A", experimentKey = "race_test")
    class TestRaceStrategy : TestStrategy { override fun execute() = "A" }
}
