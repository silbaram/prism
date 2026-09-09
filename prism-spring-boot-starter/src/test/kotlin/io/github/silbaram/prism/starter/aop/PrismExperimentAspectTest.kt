package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismUserId
import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import kotlin.test.*

class PrismExperimentAspectTest {
    private val transport = mockk<PrismClient>()
    private val target = ExposureServiceImpl()
    private val service: ExposureService = AspectJProxyFactory(target).apply {
        addAspect(PrismExperimentAspect(PrismExperimentClient(transport)))
    }.getProxy()

    @Test
    fun `implementation parameter annotations are resolved through a JDK proxy before execution`() {
        every { transport.assign("u", "e") } answers {
            assertEquals(0, target.calls)
            AssignmentResponse("u", "e", "A", "0000", "Success")
        }
        assertEquals(42, service.expose("u"))
        assertEquals(1, target.calls)
        verify(exactly = 1) { transport.assign("u", "e") }
    }

    @Test
    fun `network failure does not prevent business execution`() {
        every { transport.assign("u", "e") } throws IllegalStateException("offline")
        assertEquals(42, service.expose("u"))
    }

    @Test
    fun `missing null blank or duplicate user annotations fail explicitly before business execution`() {
        assertFailsWith<IllegalArgumentException> { service.missing("u") }
        assertFailsWith<IllegalArgumentException> { service.expose(null) }
        assertFailsWith<IllegalArgumentException> { service.expose(" ") }
        assertFailsWith<IllegalArgumentException> { service.duplicate("u", "v") }
        assertEquals(0, target.calls)
        verify { transport wasNot Called }
    }

    interface ExposureService {
        fun expose(userId: String?): Int
        fun missing(userId: String): Int
        fun duplicate(first: String, second: String): Int
    }
    class ExposureServiceImpl : ExposureService {
        var calls = 0
        @PrismExperiment("e")
        override fun expose(@PrismUserId userId: String?): Int { calls++; return 42 }
        @PrismExperiment("e")
        override fun missing(userId: String): Int { calls++; return 42 }
        @PrismExperiment("e")
        override fun duplicate(@PrismUserId first: String, @PrismUserId second: String): Int { calls++; return 42 }
    }
}
