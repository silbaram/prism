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
    fun `Issue 2 재현 - Spring AOP 프록시 객체는 어노테이션을 찾지 못한다`() {
        // Given: variant A 할당
        every { mockPrismClient.assign("user-123", "proxy_test") } returns AssignmentResponse(
            userId = "user-123",
            experimentKey = "proxy_test",
            variant = "A",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        // 실제 전략 객체 생성
        val actualStrategy = TestProxyStrategyA()

        // Spring AOP 프록시로 감싸기 (예: @Transactional, @Async 등에서 발생)
        val proxyFactory = ProxyFactory(actualStrategy)
        proxyFactory.isProxyTargetClass = true  // CGLIB 프록시 사용
        val proxiedStrategy = proxyFactory.proxy as TestProxyStrategy

        println("🔍 실제 클래스: ${actualStrategy.javaClass.name}")
        println("🔍 프록시 클래스: ${proxiedStrategy.javaClass.name}")
        println("🔍 실제 클래스의 어노테이션: ${actualStrategy.javaClass.getAnnotation(PrismStrategy::class.java)}")
        println("🔍 프록시 클래스의 어노테이션: ${proxiedStrategy.javaClass.getAnnotation(PrismStrategy::class.java)}")

        // ApplicationContext가 프록시 객체를 반환하도록 설정
        every { mockApplicationContext.getBeansOfType(TestProxyStrategy::class.java) } returns mapOf(
            "strategyA" to proxiedStrategy  // ⚠️ 프록시 객체를 반환!
        )

        // When: 전략 선택 시도
        val exception = try {
            resolver.resolve<TestProxyStrategy>("user-123", "proxy_test")
            null
        } catch (e: IllegalStateException) {
            e
        }

        // Then: 프록시 객체는 어노테이션을 찾지 못해서 전략을 발견하지 못함
        if (exception != null) {
            println("❌ Issue 2 재현됨: 프록시 객체에서 @PrismStrategy 어노테이션을 찾지 못함")
            assertTrue(exception.message!!.contains("@PrismStrategy를 찾을 수 없습니다"))
        } else {
            println("✅ 프록시 객체에서도 어노테이션을 정상적으로 찾음 (Issue 2가 이미 수정됨)")
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
