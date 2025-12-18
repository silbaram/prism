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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun `동시성 테스트 - 여러 스레드가 동시에 라우팅을 호출해도 안전하다`() {
        // Given: variant A, B, control을 번갈아 할당
        every { mockPrismClient.assign(any(), "checkout_discount") } answers {
            val userId = firstArg<String>()
            val userNumber = userId.substringAfter("user-").toInt()
            val variant = when (userNumber % 3) {
                0 -> "A"
                1 -> "B"
                else -> "control"
            }
            AssignmentResponse(
                userId = userId,
                experimentKey = "checkout_discount",
                variant = variant,
                resultCode = ResponseCode.SUCCESS.code,
                resultMessage = "Success"
            )
        }

        // 100개의 스레드로 동시에 1000번씩 호출
        val threadCount = 100
        val iterationsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = ConcurrentHashMap.newKeySet<Throwable>()
        val successCount = AtomicInteger(0)

        // When: 여러 스레드가 동시에 라우팅 호출
        repeat(threadCount) { threadIndex ->
            executor.submit {
                try {
                    repeat(iterationsPerThread) { iteration ->
                        val userId = "user-${threadIndex * iterationsPerThread + iteration}"
                        val result = router.route<Int>(testService, userId, "checkout_discount", 1000)

                        // 결과 검증
                        val userNumber = userId.substringAfter("user-").toInt()
                        val expected = when (userNumber % 3) {
                            0 -> 900   // A: 10% 할인
                            1 -> 800   // B: 20% 할인
                            else -> 1000  // control: 할인 없음
                        }
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
        // Given: 첫 번째 호출에서만 캐시 미스 발생
        var callCount = 0
        every { mockPrismClient.assign(any(), "new_experiment") } answers {
            callCount++
            AssignmentResponse(
                userId = firstArg(),
                experimentKey = "new_experiment",
                variant = "A",
                resultCode = ResponseCode.SUCCESS.code,
                resultMessage = "Success"
            )
        }

        // 새로운 테스트 서비스 (캐시에 없음)
        val newService = object {
            @PrismVariantMethod(variant = "A", experimentKey = "new_experiment")
            fun processA(amount: Int): Int = amount * 2
        }

        // 10개의 스레드가 동시에 첫 호출 (캐시 미스)
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val results = ConcurrentHashMap<Int, Int>()

        // When: 여러 스레드가 동시에 첫 호출 (캐시 미스)
        repeat(threadCount) { threadIndex ->
            executor.submit {
                try {
                    val result = router.route<Int>(newService, "user-$threadIndex", "new_experiment", 1000)
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
            assertEquals(2000, result, "Result mismatch")
        }

        println("✅ 캐시 미스 동시성 테스트 성공: ${results.size}개의 요청이 모두 정상 처리됨")
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
