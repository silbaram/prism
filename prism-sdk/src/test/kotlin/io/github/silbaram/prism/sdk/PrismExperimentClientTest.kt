package io.github.silbaram.prism.sdk

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.mockk.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PrismExperimentClientTest {
    private val transport = mockk<PrismClient>()
    private val client = PrismExperimentClient(transport)
    private fun assigned(user: String = "u", experiment: String = "e") =
        AssignmentResponse(user, experiment, "A", "0000", "Success")

    @Test
    fun `prior explicit exposure allows tracking without lookup or reassignment`() {
        every { transport.assign("u", "e") } returns assigned()
        every { transport.trackConversion("u", "e", "purchase") } returns true
        val outcome = client.assign("u", "e")
        assertTrue(outcome.assigned)
        assertTrue(client.trackIfAssigned("u", "e", "purchase"))
        verify(exactly = 1) { transport.assign(any(), any()) }
        verify(exactly = 0) { transport.getAssignment(any(), any()) }
    }

    @Test
    fun `missing exposure is never created and failures are not retained`() {
        every { transport.getAssignment("u", "e") } returnsMany listOf(
            AssignmentResponse("u", "e", null, "9100", "No exposure"), assigned())
        every { transport.trackConversion("u", "e", "purchase") } returns true
        assertFalse(client.trackIfAssigned("u", "e", "purchase"))
        assertTrue(client.trackIfAssigned("u", "e", "purchase"))
        assertTrue(client.trackIfAssigned("u", "e", "purchase"))
        verify(exactly = 2) { transport.getAssignment("u", "e") }
        verify(exactly = 0) { transport.assign(any(), any()) }
    }

    @Test
    fun `cache keys isolate users and experiments`() {
        every { transport.getAssignment(any(), any()) } answers { assigned(firstArg(), secondArg()) }
        every { transport.trackConversion(any(), any(), any()) } returns true
        listOf("u" to "e", "u2" to "e", "u" to "e2").forEach { (u, e) ->
            repeat(2) { assertTrue(client.trackIfAssigned(u, e, "purchase")) }
        }
        verify(exactly = 3) { transport.getAssignment(any(), any()) }
    }

    @Test
    fun `zero TTL disables caching and each explicit assignment still records exposure`() {
        val noCache = PrismExperimentClient(transport, Duration.ZERO)
        every { transport.getAssignment("u", "e") } returns assigned()
        every { transport.assign("u", "e") } returns assigned()
        every { transport.trackConversion("u", "e", "purchase") } returns true
        repeat(2) { assertTrue(noCache.trackIfAssigned("u", "e", "purchase")) }
        repeat(2) { client.assign("u", "e") }
        verify(exactly = 2) { transport.getAssignment("u", "e") }
        verify(exactly = 2) { transport.assign("u", "e") }
    }

    @Test
    fun `server rejection evicts cached exposure and returns false`() {
        every { transport.getAssignment("u", "e") } returns assigned()
        every { transport.trackConversion("u", "e", "purchase") } returns false
        repeat(2) { assertFalse(client.trackIfAssigned("u", "e", "purchase")) }
        verify(exactly = 2) { transport.getAssignment("u", "e") }
    }

    @Test
    fun `concurrent lookups of the same user share one lookup`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        every { transport.getAssignment("u", "e") } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            assigned()
        }
        every { transport.trackConversion("u", "e", "purchase") } returns true
        try {
            val tasks = (1..8).map { executor.submit<Boolean> { client.trackIfAssigned("u", "e", "purchase") } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            tasks.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            verify(exactly = 1) { transport.getAssignment("u", "e") }
            verify(exactly = 8) { transport.trackConversion("u", "e", "purchase") }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `assignment failures and mismatched responses never authorize tracking`() {
        every { transport.assign("u", "e") } throws IllegalStateException("offline")
        assertFalse(client.assign("u", "e").assigned)
        every { transport.getAssignment("u", "e") } returns assigned("another-user")
        assertFalse(client.trackIfAssigned("u", "e", "purchase"))
        verify(exactly = 0) { transport.trackConversion(any(), any(), any()) }
    }

    @Test
    fun `masking uses one policy for blanks short and long identifiers`() {
        listOf("", "  ", "a", "abcd").forEach { assertEquals("***", maskUserId(it)) }
        assertEquals("us***45", maskUserId("user12345"))
    }
}
