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
import org.springframework.aop.framework.ProxyFactory
import org.springframework.context.ApplicationContext

/**
 * PrismStrategyResolver의 프록시 객체 어노테이션 감지 문제를 재현하는 테스트
 */
class PrismStrategyResolverProxyTest {

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
    fun `strategy resolution keeps JDK and CGLIB proxies and their advice`() {
        every { mockPrismClient.assign("user-123", "proxy_test") } returns
            AssignmentResponse("user-123", "proxy_test", "A", "0000", "Success")
        for (cglib in listOf(false, true)) {
            var adviceCalls = 0
            val factory = ProxyFactory(TestProxyStrategyA())
            factory.isProxyTargetClass = cglib
            factory.addAdvice(org.aopalliance.intercept.MethodInterceptor { invocation ->
                adviceCalls++
                invocation.proceed()
            })
            val proxy = factory.proxy as TestProxyStrategy
            every { mockApplicationContext.getBeansOfType(TestProxyStrategy::class.java) } returns mapOf("strategyA" to proxy)
            val selected = PrismStrategyResolver(mockApplicationContext, prismExperimentClient)
                .resolve<TestProxyStrategy>("user-123", "proxy_test")
            assertSame(proxy, selected)
            assertEquals("A", selected.execute())
            assertEquals(1, adviceCalls)
        }
    }

    @Test
    fun `프록시가 아닌 일반 객체는 어노테이션을 정상적으로 찾는다`() {
        // Given: variant A 할당
        every { mockPrismClient.assign("user-456", "proxy_test") } returns AssignmentResponse(
            userId = "user-456",
            experimentKey = "proxy_test",
            variant = "A",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        // 프록시가 아닌 일반 객체
        val normalStrategy = TestProxyStrategyA()

        every { mockApplicationContext.getBeansOfType(TestProxyStrategy::class.java) } returns mapOf(
            "strategyA" to normalStrategy  // 일반 객체
        )

        // When: 전략 선택
        val strategy = resolver.resolve<TestProxyStrategy>("user-456", "proxy_test")

        // Then: 정상적으로 전략을 찾음
        val result = strategy.execute()
        assertEquals("A", result)
        println("✅ 일반 객체는 어노테이션을 정상적으로 찾음")
    }

    // 테스트용 인터페이스 및 구현체
    interface TestProxyStrategy {
        fun execute(): String
    }

    @PrismStrategy(variant = "A", experimentKey = "proxy_test")
    open class TestProxyStrategyA : TestProxyStrategy {
        override fun execute(): String = "A"
    }
}
