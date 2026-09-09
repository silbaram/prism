package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.*
import io.github.silbaram.prism.starter.service.PrismConversionTracker
import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PrismTrackConversionAspectTest {
    private val transport = mockk<PrismClient>()
    private val client = PrismExperimentClient(transport)
    private fun proxy(reverse: Boolean = false, cglib: Boolean = false): EventService {
        val aspects = listOf(PrismExperimentAspect(client), PrismTrackConversionAspect(client))
        return AspectJProxyFactory(EventServiceImpl()).apply {
            isProxyTargetClass = cglib
            (if (reverse) aspects.reversed() else aspects).forEach { addAspect(it) }
        }.getProxy()
    }
    private fun allowExposure() {
        every { transport.assign(any(), any()) } answers { AssignmentResponse(firstArg(), secondArg(), "A", "0000", "Success") }
        every { transport.getAssignment(any(), any()) } answers { AssignmentResponse(firstArg(), secondArg(), "A", "0000", "Success") }
        every { transport.trackConversion(any(), any(), any()) } returns true
    }

    @Test
    fun `separate method can track an existing exposure through a JDK proxy`() {
        allowExposure()
        assertTrue(proxy().purchase("u", true))
        verify(exactly = 1) { transport.getAssignment("u", "checkout") }
        verify(exactly = 1) { transport.trackConversion("u", "checkout", "purchase") }
        verify(exactly = 0) { transport.assign(any(), any()) }
    }

    @Test
    fun `missing exposure skips conversion without creating exposure`() {
        every { transport.getAssignment(any(), any()) } returns AssignmentResponse("u", "checkout", null, "9100", "Missing")
        assertTrue(proxy().purchase("u", true))
        verify(exactly = 0) { transport.trackConversion(any(), any(), any()) }
        verify(exactly = 0) { transport.assign(any(), any()) }
    }

    @Test
    fun `both aspect orders work with JDK and class proxies`() {
        allowExposure()
        for (reverse in listOf(false, true)) for (cglib in listOf(false, true)) {
            assertEquals(42, proxy(reverse, cglib).combined("u-$reverse-$cglib"))
        }
        verify(exactly = 4) { transport.assign(any(), "checkout") }
        verify(exactly = 4) { transport.trackConversion(any(), "checkout", "view") }
    }

    @Test
    fun `repeatable events evaluate conditions independently`() {
        allowExposure()
        val service = proxy()
        assertFalse(service.purchase("u", false))
        assertNull(service.recommend("u", null))
        assertEquals("item", service.recommend("u", "item"))
        verify(exactly = 1) { transport.trackConversion("u", "checkout", "failed") }
        verify(exactly = 1) { transport.trackConversion("u", "recommend", "missing") }
        verify(exactly = 1) { transport.trackConversion("u", "recommend", "found") }
        verify(exactly = 0) { transport.trackConversion("u", "checkout", "purchase") }
    }

    @Test
    fun `exception events are opt in and cannot replace original exception`() {
        allowExposure()
        every { transport.trackConversion("u", "checkout", "error") } throws IllegalStateException("tracking failed")
        val failure = assertFailsWith<UnsupportedOperationException> { proxy().fail("u") }
        assertEquals("business failed", failure.message)
        verify(exactly = 1) { transport.trackConversion("u", "checkout", "error") }
        verify(exactly = 0) { transport.trackConversion("u", "checkout", "purchase") }
        verify(exactly = 0) { transport.trackConversion("u", "checkout", "null-result") }
    }

    @Test
    fun `user annotation errors surface before method side effects`() {
        assertFailsWith<IllegalArgumentException> { proxy().missing("u") }
        verify { transport wasNot Called }
    }

    @Test
    fun `cached exposure works on another thread and does not leak into another experiment`() {
        allowExposure()
        client.assign("u", "checkout")
        every { transport.getAssignment("u", "other") } returns AssignmentResponse("u", "other", null, "9100", "Missing")
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.submit<Boolean> { PrismConversionTracker(client).trackConversionSafe("u", "checkout", "purchase") }.get(5, TimeUnit.SECONDS))
            assertFalse(PrismConversionTracker(client).trackConversionSafe("u", "other", "purchase"))
            verify(exactly = 0) { transport.getAssignment("u", "checkout") }
            verify(exactly = 0) { transport.trackConversion("u", "other", any()) }
        } finally { executor.shutdownNow() }
    }

    interface EventService {
        fun purchase(userId: String, success: Boolean): Boolean
        fun combined(userId: String): Int
        fun recommend(userId: String, item: String?): String?
        fun fail(userId: String)
        fun missing(userId: String)
    }
    open class EventServiceImpl : EventService {
        @PrismTrackConversion("checkout", "purchase", trackWhen = TrackCondition.RETURN_TRUE)
        @PrismTrackConversion("checkout", "failed", trackWhen = TrackCondition.RETURN_FALSE)
        override fun purchase(@PrismUserId userId: String, success: Boolean) = success

        @PrismExperiment("checkout")
        @PrismTrackConversion("checkout", "view")
        override fun combined(@PrismUserId userId: String) = 42

        @PrismTrackConversion("recommend", "found", trackWhen = TrackCondition.NOT_NULL)
        @PrismTrackConversion("recommend", "missing", trackWhen = TrackCondition.IS_NULL)
        override fun recommend(@PrismUserId userId: String, item: String?) = item

        @PrismTrackConversion("checkout", "purchase")
        @PrismTrackConversion("checkout", "error", trackOnException = true)
        @PrismTrackConversion("checkout", "null-result", trackOnException = true, trackWhen = TrackCondition.IS_NULL)
        override fun fail(@PrismUserId userId: String) { throw UnsupportedOperationException("business failed") }

        @PrismTrackConversion("checkout", "purchase")
        override fun missing(userId: String) { error("Business code must not run") }
    }
}
