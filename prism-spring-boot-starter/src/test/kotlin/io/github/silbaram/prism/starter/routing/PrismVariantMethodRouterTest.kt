package io.github.silbaram.prism.starter.routing

import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.sdk.AssignmentOutcome
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismVariantMethod
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PrismVariantMethodRouterTest {

    private lateinit var mockPrismClient: PrismClient
    private lateinit var prismExperimentClient: PrismExperimentClient
    private lateinit var router: PrismVariantMethodRouter
    private lateinit var testService: TestCheckoutService

    @BeforeEach
    fun setUp() {
        mockPrismClient = mockk(relaxed = true)
        prismExperimentClient = PrismExperimentClient(mockPrismClient)
        router = PrismVariantMethodRouter(prismExperimentClient)
        testService = TestCheckoutService()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `variant A에 맞는 메서드가 실행된다`() {
        // Given: variant A가 할당됨
        every { mockPrismClient.assign("user-123", "checkout_discount") } returns AssignmentResponse(
            userId = "user-123",
            experimentKey = "checkout_discount",
            variant = "A",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        // When: 라우팅 실행
        val result = router.route<Int>(testService, "user-123", "checkout_discount", 1000)

        // Then: variant A의 메서드가 실행됨 (10% 할인)
        assertEquals(900, result)
    }

    @Test
    fun `variant B에 맞는 메서드가 실행된다`() {
        // Given: variant B가 할당됨
        every { mockPrismClient.assign("user-456", "checkout_discount") } returns AssignmentResponse(
            userId = "user-456",
            experimentKey = "checkout_discount",
            variant = "B",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        // When: 라우팅 실행
        val result = router.route<Int>(testService, "user-456", "checkout_discount", 1000)

        // Then: variant B의 메서드가 실행됨 (20% 할인)
        assertEquals(800, result)
    }

    @Test
    fun `variant가 null이면 control 메서드가 실행된다`() {
        // Given: variant가 할당되지 않음
        every { mockPrismClient.assign("user-789", "checkout_discount") } returns AssignmentResponse(
            userId = "user-789",
            experimentKey = "checkout_discount",
            variant = null,
            resultCode = ResponseCode.EXPERIMENT_NOT_FOUND.code,
            resultMessage = "Experiment not found"
        )

        // When: 라우팅 실행
        val result = router.route<Int>(testService, "user-789", "checkout_discount", 1000)

        // Then: control 메서드가 실행됨 (할인 없음)
        assertEquals(1000, result)
    }

    @Test
    fun `해당 variant의 메서드가 없으면 예외가 발생한다`() {
        // Given: 존재하지 않는 variant가 할당됨
        every { mockPrismClient.assign("user-999", "checkout_discount") } returns AssignmentResponse(
            userId = "user-999",
            experimentKey = "checkout_discount",
            variant = "C",  // 존재하지 않는 variant
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        // When & Then: 예외 발생
        val exception = assertThrows<NoSuchMethodException> {
            router.route<Int>(testService, "user-999", "checkout_discount", 1000)
        }
        assertTrue(exception.message!!.contains("variant='C'"))
    }

    @Test
    fun `메서드 캐싱이 동작한다`() {
        // Given: 같은 조건으로 두 번 호출
        every { mockPrismClient.assign("user-123", "checkout_discount") } returns AssignmentResponse(
            userId = "user-123",
            experimentKey = "checkout_discount",
            variant = "A",
            resultCode = ResponseCode.SUCCESS.code,
            resultMessage = "Success"
        )

        // When: 첫 번째 호출
        val result1 = router.route<Int>(testService, "user-123", "checkout_discount", 1000)
        // When: 두 번째 호출
        val result2 = router.route<Int>(testService, "user-123", "checkout_discount", 2000)

        // Then: 두 번 모두 정상 실행됨 (캐싱 덕분에 빠름)
        assertEquals(900, result1)
        assertEquals(1800, result2)
    }

    // 테스트용 서비스 클래스
    class TestCheckoutService {

        @PrismVariantMethod(variant = "A", experimentKey = "checkout_discount")
        fun processCheckoutA(amount: Int): Int {
            return (amount * 0.9).toInt()  // 10% 할인
        }

        @PrismVariantMethod(variant = "B", experimentKey = "checkout_discount")
        fun processCheckoutB(amount: Int): Int {
            return (amount * 0.8).toInt()  // 20% 할인
        }

        @PrismVariantMethod(variant = "control", experimentKey = "checkout_discount")
        fun processCheckoutControl(amount: Int): Int {
            return amount  // 할인 없음
        }
    }
}
