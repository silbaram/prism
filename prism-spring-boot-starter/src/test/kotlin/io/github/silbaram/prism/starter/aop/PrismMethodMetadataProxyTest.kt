package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismTrackConversion
import io.github.silbaram.prism.starter.annotation.PrismUserId
import io.mockk.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import kotlin.test.*

class PrismMethodMetadataProxyTest {
    private val transport = mockk<PrismClient>()

    private fun <T> proxy(target: Any, cglib: Boolean): T {
        val client = PrismExperimentClient(transport)
        return AspectJProxyFactory(target).apply {
            isProxyTargetClass = cglib
            addAspect(PrismExperimentAspect(client))
            addAspect(PrismTrackConversionAspect(client))
        }.getProxy()
    }

    private fun allowTracking() {
        every { transport.assign("u", "e") } returns AssignmentResponse("u", "e", "A", "0000", "Success")
        every { transport.getAssignment("u", "e") } returns AssignmentResponse("u", "e", "A", "0000", "Success")
        every { transport.trackConversion("u", "e", "purchase") } returns true
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `both aspects resolve user annotation inherited from an interface`(cglib: Boolean) {
        allowTracking()
        val service = proxy<Contract>(ContractImpl(), cglib)
        assertEquals(42, service.execute("payload", "u"))
        verify(exactly = 1) { transport.assign("u", "e") }
        verify(exactly = 1) { transport.trackConversion("u", "e", "purchase") }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `generic interface bridge resolves only the matching overload`(cglib: Boolean) {
        allowTracking()
        val service = proxy<GenericContract<String>>(GenericImpl(), cglib)
        assertEquals(42, service.execute("payload", "u"))
        verify(exactly = 1) { transport.assign("u", "e") }
        verify(exactly = 1) { transport.trackConversion("u", "e", "purchase") }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `overridden method inherits user annotation from its superclass`(cglib: Boolean) {
        allowTracking()
        assertEquals(42, proxy<PlainContract>(ChildService(), cglib).execute("payload", "u"))
        verify(exactly = 1) { transport.trackConversion("u", "e", "purchase") }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `conflicting parameter positions across interfaces fail before business execution`(cglib: Boolean) {
        val target = ConflictingService()
        assertFailsWith<IllegalArgumentException> { proxy<Contract>(target, cglib).execute("payload", "u") }
        assertEquals(0, target.calls)
        verify { transport wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `metadata cache distinguishes target classes that share the same inherited method`(cglib: Boolean) {
        allowTracking()
        assertEquals(42, proxy<Contract>(FirstUserService(), cglib).execute("payload", "u"))
        assertEquals(42, proxy<OtherContract>(SecondUserService(), cglib).execute("u", "payload"))
        verify(exactly = 2) { transport.trackConversion("u", "e", "purchase") }
    }

    interface Contract { fun execute(first: String, @PrismUserId second: String): Int }
    interface OtherContract { fun execute(@PrismUserId first: String, second: String): Int }
    interface PlainContract { fun execute(first: String, second: String): Int }
    interface GenericContract<T> { fun execute(first: String, @PrismUserId second: T): Int }

    open class ContractImpl : Contract {
        @PrismExperiment("e")
        @PrismTrackConversion("e", "purchase")
        override fun execute(first: String, second: String) = 42
    }

    open class GenericImpl : GenericContract<String> {
        @PrismExperiment("e")
        @PrismTrackConversion("e", "purchase")
        override fun execute(first: String, second: String) = 42

        // The annotation on an unrelated overload must not create a false conflict.
        fun execute(@PrismUserId userId: Int, payload: String) = userId
    }

    open class ParentService : PlainContract {
        override fun execute(first: String, @PrismUserId second: String) = 0
    }

    open class ChildService : ParentService() {
        @PrismTrackConversion("e", "purchase")
        override fun execute(first: String, second: String) = 42
    }

    open class ConflictingService : Contract, OtherContract {
        var calls = 0
        @PrismExperiment("e")
        override fun execute(first: String, second: String): Int { calls++; return 42 }
    }

    open class SharedImplementation {
        @PrismTrackConversion("e", "purchase")
        open fun execute(first: String, second: String) = 42
    }
    open class FirstUserService : SharedImplementation(), Contract
    open class SecondUserService : SharedImplementation(), OtherContract
}
