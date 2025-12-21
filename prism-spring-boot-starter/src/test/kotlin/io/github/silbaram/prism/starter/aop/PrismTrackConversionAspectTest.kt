package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.starter.annotation.PrismTrackConversion
import io.github.silbaram.prism.starter.annotation.PrismUserId
import io.github.silbaram.prism.starter.annotation.TrackCondition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory

class PrismTrackConversionAspectTest {

    private lateinit var prismClient: PrismClient
    private lateinit var aspect: PrismTrackConversionAspect
    private lateinit var testService: TestServiceImpl

    @BeforeEach
    fun setUp() {
        prismClient = mock(PrismClient::class.java)
        aspect = PrismTrackConversionAspect(prismClient)

        // AOP 프록시 설정
        val target = TestServiceImpl()
        val factory = AspectJProxyFactory(target)
        factory.addAspect(aspect)
        testService = factory.getProxy() as TestServiceImpl
    }

    @AfterEach
    fun tearDown() {
        PrismContext.clear()
    }

    @Test
    fun `정상 실행 후 할당된 경우 전환 추적`() {
        // given
        val userId = "user123"
        val experimentKey = "checkout-flow"
        val eventName = "purchase"

        // PrismContext에 할당 정보 설정 (실제로 할당받은 것으로 시뮬레이션)
        PrismContext.setCurrentVariant("B", wasActualAssignment = true)

        // when
        val result = testService.completePurchaseWithAnnotation(userId, 10000)

        // then
        assertTrue(result)
        verify(prismClient).trackConversion(userId, experimentKey, eventName)
    }

    @Test
    fun `할당되지 않은 경우 전환 추적 스킵`() {
        // given
        val userId = "user123"

        // PrismContext에 할당 안 됨으로 설정
        PrismContext.setCurrentVariant(null, wasActualAssignment = false)

        // when
        val result = testService.completePurchaseWithAnnotation(userId, 10000)

        // then
        assertTrue(result)
        verify(prismClient, never()).trackConversion(anyString(), anyString(), anyString())
    }

    @Test
    fun `userId를 찾을 수 없는 경우 전환 추적 스킵`() {
        // given
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when
        val result = testService.purchaseWithoutUserId(10000)

        // then
        assertTrue(result)
        verify(prismClient, never()).trackConversion(anyString(), anyString(), anyString())
    }

    @Test
    fun `예외 발생 시 trackOnException=false이면 전환 추적 안 함`() {
        // given
        val userId = "user123"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when & then
        assertThrows<RuntimeException> {
            testService.purchaseWithExceptionDefault(userId)
        }

        // 예외 발생으로 전환 추적 안 됨
        verify(prismClient, never()).trackConversion(anyString(), anyString(), anyString())
    }

    @Test
    fun `예외 발생 시 trackOnException=true이면 전환 추적`() {
        // given
        val userId = "user456"
        val experimentKey = "checkout-flow"
        val eventName = "purchase_attempt"
        PrismContext.setCurrentVariant("B", wasActualAssignment = true)

        // when & then
        assertThrows<RuntimeException> {
            testService.purchaseWithExceptionTracking(userId)
        }

        // 예외 발생해도 전환 추적됨
        verify(prismClient).trackConversion(userId, experimentKey, eventName)
    }

    @Test
    fun `파라미터 이름으로 userId 추출 후 전환 추적`() {
        // given
        val userId = "user789"
        val experimentKey = "checkout-flow"
        val eventName = "purchase"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when
        val result = testService.completePurchaseWithParamName(userId, 5000)

        // then
        assertTrue(result)
        verify(prismClient).trackConversion(userId, experimentKey, eventName)
    }

    @Test
    fun `전환 추적 실패해도 비즈니스 로직은 정상 실행`() {
        // given
        val userId = "user999"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // PrismClient가 예외 발생
        doThrow(RuntimeException("전환 API 오류"))
            .`when`(prismClient).trackConversion(anyString(), anyString(), anyString())

        // when
        val result = testService.completePurchaseWithAnnotation(userId, 10000)

        // then
        // 비즈니스 로직은 정상 완료
        assertTrue(result)
        verify(prismClient).trackConversion(userId, "checkout-flow", "purchase")
    }

    @Test
    fun `trackWhen=RETURN_TRUE - true 반환 시 전환 추적됨`() {
        // given
        val userId = "user111"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when
        val result = testService.processPaymentReturnTrue(userId)

        // then
        assertTrue(result)
        verify(prismClient).trackConversion(userId, "payment-flow", "payment_success")
    }

    @Test
    fun `trackWhen=RETURN_FALSE - false 반환 시 전환 추적됨`() {
        // given
        val userId = "user222"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when
        val result = testService.processPaymentReturnFalse(userId)

        // then
        assertFalse(result)
        verify(prismClient).trackConversion(userId, "payment-flow", "payment_failed")
    }

    @Test
    fun `trackWhen=NOT_NULL - null이 아닐 때 전환 추적됨`() {
        // given
        val userId = "user333"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when
        val result = testService.findProductNotNull(userId)

        // then
        assertNotNull(result)
        assertEquals("product-123", result)
        verify(prismClient).trackConversion(userId, "recommendation", "found_product")
    }

    @Test
    fun `trackWhen=IS_NULL - null일 때 전환 추적됨`() {
        // given
        val userId = "user444"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when
        val result = testService.findProductNull(userId)

        // then
        assertNull(result)
        verify(prismClient).trackConversion(userId, "recommendation", "no_product")
    }

    @Test
    fun `여러 이벤트 동시 추적 - 두 이벤트 모두 기록됨`() {
        // given
        val userId = "user555"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when
        testService.showSignupForm(userId)

        // then
        verify(prismClient).trackConversion(userId, "signup", "page_viewed")
        verify(prismClient).trackConversion(userId, "signup", "form_started")
    }

    @Test
    fun `여러 이벤트 + 조건부 추적 - 조건에 따라 선택적 기록`() {
        // given
        val userId = "user666"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when - 성공 케이스
        val result = testService.submitSignup(userId, true)

        // then
        assertTrue(result)
        verify(prismClient).trackConversion(userId, "signup", "submitted")
        verify(prismClient).trackConversion(userId, "signup", "success")
    }

    @Test
    fun `여러 이벤트 + 조건부 추적 - 실패 시 일부만 기록`() {
        // given
        val userId = "user777"
        PrismContext.setCurrentVariant("A", wasActualAssignment = true)

        // when - 실패 케이스
        val result = testService.submitSignup(userId, false)

        // then
        assertFalse(result)
        verify(prismClient).trackConversion(userId, "signup", "submitted")
        verify(prismClient, never()).trackConversion(userId, "signup", "success")
    }

    // 테스트용 서비스 구현
    open class TestServiceImpl {

        @PrismTrackConversion(
            experimentKey = "checkout-flow",
            eventName = "purchase"
        )
        open fun completePurchaseWithAnnotation(
            @PrismUserId userId: String,
            amount: Int
        ): Boolean {
            // 구매 로직
            return amount > 0
        }

        @PrismTrackConversion(
            experimentKey = "checkout-flow",
            eventName = "purchase",
            userIdParam = "userId"
        )
        open fun completePurchaseWithParamName(userId: String, amount: Int): Boolean {
            return amount > 0
        }

        @PrismTrackConversion(
            experimentKey = "checkout-flow",
            eventName = "purchase"
        )
        open fun purchaseWithoutUserId(amount: Int): Boolean {
            // userId 파라미터 없음 → 전환 추적 스킵됨
            return amount > 0
        }

        @PrismTrackConversion(
            experimentKey = "checkout-flow",
            eventName = "purchase_failed",
            trackOnException = false  // 기본값
        )
        open fun purchaseWithExceptionDefault(@PrismUserId userId: String): Boolean {
            throw RuntimeException("결제 실패")
        }

        @PrismTrackConversion(
            experimentKey = "checkout-flow",
            eventName = "purchase_attempt",
            trackOnException = true  // 예외 발생 시에도 추적
        )
        open fun purchaseWithExceptionTracking(@PrismUserId userId: String): Boolean {
            throw RuntimeException("결제 실패")
        }

        @PrismTrackConversion(
            experimentKey = "payment-flow",
            eventName = "payment_success",
            trackWhen = TrackCondition.RETURN_TRUE
        )
        open fun processPaymentReturnTrue(@PrismUserId userId: String): Boolean {
            return true  // true 반환 시에만 추적
        }

        @PrismTrackConversion(
            experimentKey = "payment-flow",
            eventName = "payment_failed",
            trackWhen = TrackCondition.RETURN_FALSE
        )
        open fun processPaymentReturnFalse(@PrismUserId userId: String): Boolean {
            return false  // false 반환 시에만 추적
        }

        @PrismTrackConversion(
            experimentKey = "recommendation",
            eventName = "found_product",
            trackWhen = TrackCondition.NOT_NULL
        )
        open fun findProductNotNull(@PrismUserId userId: String): String? {
            return "product-123"  // null 아닐 때만 추적
        }

        @PrismTrackConversion(
            experimentKey = "recommendation",
            eventName = "no_product",
            trackWhen = TrackCondition.IS_NULL
        )
        open fun findProductNull(@PrismUserId userId: String): String? {
            return null  // null일 때만 추적
        }

        // 여러 이벤트 동시 추적
        @PrismTrackConversion(experimentKey = "signup", eventName = "page_viewed")
        @PrismTrackConversion(experimentKey = "signup", eventName = "form_started")
        open fun showSignupForm(@PrismUserId userId: String) {
            // 두 이벤트 모두 추적됨
        }

        // 여러 이벤트 + 조건부
        @PrismTrackConversion(experimentKey = "signup", eventName = "submitted")
        @PrismTrackConversion(
            experimentKey = "signup",
            eventName = "success",
            trackWhen = TrackCondition.RETURN_TRUE
        )
        open fun submitSignup(@PrismUserId userId: String, success: Boolean): Boolean {
            return success
        }
    }
}
