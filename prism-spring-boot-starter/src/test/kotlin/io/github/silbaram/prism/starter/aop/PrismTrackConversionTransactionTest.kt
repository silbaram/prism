package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.*
import io.github.silbaram.prism.starter.PrismAutoConfiguration
import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.core.Ordered
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PrismTrackConversionTransactionTest {
    private val transport = mockk<PrismClient>()
    private val client = PrismExperimentClient(transport)
    private val dataSource = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
    private val jdbc = JdbcTemplate(dataSource).apply { execute("CREATE TABLE orders (user_id VARCHAR(255))") }
    private val manager = DataSourceTransactionManager(dataSource)
    private val transaction = TransactionTemplate(manager)

    init {
        every { transport.getAssignment(any(), "checkout") } answers {
            AssignmentResponse(firstArg(), "checkout", "A", "0000", "Success")
        }
        every { transport.trackConversion(any(), "checkout", any()) } returns true
        every { transport.close() } just Runs
    }

    private fun proxy(transactional: Boolean = false, conversionFirst: Boolean = false,
                      txManager: DataSourceTransactionManager = manager): PurchaseService =
        AspectJProxyFactory(PurchaseService(jdbc)).apply {
            isProxyTargetClass = true
            val interceptor = TransactionInterceptor().apply {
                transactionManager = txManager
                transactionAttributeSource = AnnotationTransactionAttributeSource()
            }
            if (transactional && !conversionFirst) addAdvice(interceptor)
            addAspect(PrismTrackConversionAspect(client))
            if (transactional && conversionFirst) addAdvice(interceptor)
        }.getProxy()

    private fun orderCount() = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Int::class.java)
    private fun verifyPurchase(count: Int) {
        verify(exactly = count) { transport.trackConversion(any(), "checkout", "purchase") }
    }

    @Test
    fun `conversion waits until the surrounding JDBC transaction commits`() {
        val service = proxy()
        assertEquals(true, transaction.execute {
            assertTrue(service.purchase("committed"))
            assertEquals(1, orderCount())
            verifyPurchase(0)
            true
        })
        assertEquals(1, orderCount())
        verifyPurchase(1)
    }

    @Test
    fun `rollback-only and an exception after a successful method never track success`() {
        val service = proxy()
        transaction.executeWithoutResult { status ->
            service.purchase("rollback-only")
            status.setRollbackOnly()
        }
        assertFailsWith<IllegalStateException> {
            transaction.executeWithoutResult {
                service.purchase("outer-failure")
                error("Failed after returning from the annotated method")
            }
        }
        assertEquals(0, orderCount())
        verifyPurchase(0)
    }

    @Test
    fun `annotation transaction advice works in either order with conversion advice`() {
        for (conversionFirst in listOf(false, true)) {
            assertTrue(proxy(transactional = true, conversionFirst = conversionFirst).purchase("order-$conversionFirst"))
        }
        assertEquals(2, orderCount())
        verifyPurchase(2)
    }

    @Test
    fun `commit failure skips conversion in either advice order and preserves the failure`() {
        val failingManager = object : DataSourceTransactionManager(dataSource) {
            override fun doCommit(status: DefaultTransactionStatus) {
                throw TransactionSystemException("Simulated database commit failure")
            }
        }.apply { isRollbackOnCommitFailure = true }
        for (conversionFirst in listOf(false, true)) {
            val error = assertFailsWith<TransactionSystemException> {
                proxy(true, conversionFirst, failingManager).purchase("commit-failed-$conversionFirst")
            }
            assertEquals("Simulated database commit failure", error.message)
        }
        assertEquals(0, orderCount())
        verifyPurchase(0)
    }

    @Test
    fun `tracking failure after commit preserves committed business data and return value`() {
        every { transport.trackConversion(any(), "checkout", "purchase") } throws IllegalStateException("Collector unavailable")
        assertEquals(true, transaction.execute { proxy().purchase("collector-failed") })
        assertEquals(1, orderCount())
        verifyPurchase(1)
    }

    @Test
    fun `requires-new conversion follows its own commit while outer transaction rolls back`() {
        val inner = TransactionTemplate(manager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
        transaction.executeWithoutResult { status ->
            jdbc.update("INSERT INTO orders VALUES (?)", "outer")
            inner.executeWithoutResult { proxy().purchase("inner") }
            verifyPurchase(1)
            status.setRollbackOnly()
        }
        assertEquals(listOf("inner"), jdbc.queryForList("SELECT user_id FROM orders", String::class.java))
        verifyPurchase(1)
    }

    @Test
    fun `afterCommit callbacks and committed event listeners track without waiting for another commit`() {
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PrismAutoConfiguration::class.java))
            .withUserConfiguration(DefaultTransactions::class.java)
            .withBean(PrismClient::class.java, { transport })
            .withBean(PlatformTransactionManager::class.java, { manager })
            .withBean(PurchaseService::class.java, { PurchaseService(jdbc) })
            .run { context ->
                assertNull(context.startupFailure)
                val service = context.getBean(PurchaseService::class.java)
                transaction.executeWithoutResult {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { service.completed("after-commit") }
                    })
                }
                verify(exactly = 1) { transport.trackConversion("after-commit", "checkout", "purchase") }
                transaction.executeWithoutResult { status ->
                    context.publishEvent("rolled-back-event")
                    status.setRollbackOnly()
                }
                verify(exactly = 0) { transport.trackConversion("rolled-back-event", "checkout", "purchase") }
                transaction.executeWithoutResult { context.publishEvent("committed-event") }
                verify(exactly = 1) { transport.trackConversion("committed-event", "checkout", "purchase") }
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
            }
    }

    @Test
    fun `requires-new rollback in committed callbacks restores the committed parent scope`() {
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PrismAutoConfiguration::class.java))
            .withUserConfiguration(DefaultTransactions::class.java)
            .withBean(PrismExperimentClient::class.java, { client })
            .withBean(PlatformTransactionManager::class.java, { manager })
            .withBean(PurchaseService::class.java, { PurchaseService(jdbc) })
            .run { context ->
                assertNull(context.startupFailure)
                val service = context.getBean(PurchaseService::class.java)
                val inner = TransactionTemplate(manager).apply {
                    propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                }
                fun callback(phase: String) {
                    inner.executeWithoutResult { status ->
                        service.purchase("inner-$phase")
                        verify(exactly = 0) { transport.trackConversion("inner-$phase", "checkout", "purchase") }
                        status.setRollbackOnly()
                    }
                    service.completed("outer-$phase")
                }
                transaction.executeWithoutResult {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { callback("commit") }
                        override fun afterCompletion(status: Int) {
                            if (status == TransactionSynchronization.STATUS_COMMITTED) callback("completion")
                        }
                    })
                }
                assertEquals(0, orderCount())
                verify(exactly = 0) { transport.trackConversion("inner-commit", "checkout", "purchase") }
                verify(exactly = 0) { transport.trackConversion("inner-completion", "checkout", "purchase") }
                verify(exactly = 1) { transport.trackConversion("outer-commit", "checkout", "purchase") }
                verify(exactly = 1) { transport.trackConversion("outer-completion", "checkout", "purchase") }
                assertFalse(PrismTransactionLifecycle.isObserved())
            }
    }

    @Test
    fun `existing transaction listeners retain ordered callbacks and failures cannot leak a completed scope`() {
        lateinit var service: PurchaseService
        manager.addListener(object : TransactionExecutionListener {
            override fun afterBegin(transaction: TransactionExecution, beginFailure: Throwable?) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE
                    override fun afterCommit() { service.completed("ordered-callback") }
                })
            }
            override fun afterCommit(transaction: TransactionExecution, commitFailure: Throwable?) {
                service.completed("execution-listener")
                error("Caller listener failed")
            }
        })
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PrismAutoConfiguration::class.java))
            .withUserConfiguration(DefaultTransactions::class.java)
            .withBean(PrismClient::class.java, { transport })
            .withBean(PlatformTransactionManager::class.java, { manager })
            .withBean(PurchaseService::class.java, { PurchaseService(jdbc) })
            .run { context ->
                assertNull(context.startupFailure)
                service = context.getBean(PurchaseService::class.java)
                val error = assertFailsWith<IllegalStateException> { transaction.executeWithoutResult { } }
                assertEquals("Caller listener failed", error.message)
                verify(exactly = 1) { transport.trackConversion("ordered-callback", "checkout", "purchase") }
                verify(exactly = 1) { transport.trackConversion("execution-listener", "checkout", "purchase") }
                transaction.executeWithoutResult { status ->
                    service.completed("next-rollback")
                    status.setRollbackOnly()
                }
                verify(exactly = 0) { transport.trackConversion("next-rollback", "checkout", "purchase") }
                assertFalse(PrismTransactionLifecycle.isObserved())
            }
    }

    @Test
    fun `managed commit failure preserves existing listeners and releases tracking scope`() {
        val begins = AtomicInteger()
        val rollbacks = AtomicInteger()
        val failingManager = object : DataSourceTransactionManager(dataSource) {
            override fun doCommit(status: DefaultTransactionStatus) {
                throw TransactionSystemException("Managed commit failure")
            }
        }.apply {
            isRollbackOnCommitFailure = true
            addListener(object : TransactionExecutionListener {
                override fun afterBegin(transaction: TransactionExecution, beginFailure: Throwable?) { begins.incrementAndGet() }
                override fun afterRollback(transaction: TransactionExecution, rollbackFailure: Throwable?) { rollbacks.incrementAndGet() }
            })
        }
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PrismAutoConfiguration::class.java))
            .withUserConfiguration(DefaultTransactions::class.java)
            .withBean(PrismClient::class.java, { transport })
            .withBean(PlatformTransactionManager::class.java, { failingManager })
            .withBean(PurchaseService::class.java, { PurchaseService(jdbc) })
            .run { context ->
                assertNull(context.startupFailure)
                val service = context.getBean(PurchaseService::class.java)
                assertFailsWith<TransactionSystemException> { service.purchase("failed-commit") }
                assertEquals(0, orderCount())
                verifyPurchase(0)
                assertEquals(1, begins.get())
                assertEquals(1, rollbacks.get())
                assertFalse(PrismTransactionLifecycle.isObserved())
                service.completed("outside-transaction")
                verifyPurchase(1)
            }
    }

    @Test
    fun `Spring auto-configuration observes rollback-only within annotation transactions`() {
        for (configuration in listOf(DefaultTransactions::class.java, OrderedTransactions::class.java)) {
            jdbc.update("DELETE FROM orders")
            clearMocks(transport, answers = false)
            ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PrismAutoConfiguration::class.java))
                .withUserConfiguration(configuration)
                .withBean(PrismClient::class.java, { transport })
                .withBean(PlatformTransactionManager::class.java, { manager })
                .withBean(PurchaseService::class.java, { PurchaseService(jdbc) })
                .run { context ->
                    assertNull(context.startupFailure)
                    val service = context.getBean(PurchaseService::class.java)
                    assertTrue(service.rollbackOnly("rollback"))
                    transaction.executeWithoutResult { assertTrue(service.nestedRollbackOnly("nested-rollback")) }
                    assertEquals(0, orderCount())
                    verifyPurchase(0)
                    // Confirm that real auto-configured advice also tracks the positive case.
                    assertTrue(service.purchase("committed"))
                    assertEquals(1, orderCount())
                    verifyPurchase(1)
                }
        }
    }

    @Test
    fun `savepoint rollback cancels its conversion while keeping work before and after it`() {
        val service = proxy()
        transaction.executeWithoutResult { status ->
            service.purchase("before")
            val savepoint = status.createSavepoint()
            service.purchase("rolled-back")
            status.rollbackToSavepoint(savepoint)
            status.releaseSavepoint(savepoint)
            service.purchase("after")
            verifyPurchase(0)
        }
        assertEquals(listOf("before", "after"), jdbc.queryForList("SELECT user_id FROM orders", String::class.java))
        verify(exactly = 1) { transport.trackConversion("before", "checkout", "purchase") }
        verify(exactly = 1) { transport.trackConversion("after", "checkout", "purchase") }
        verify(exactly = 0) { transport.trackConversion("rolled-back", "checkout", "purchase") }
    }

    @Test
    fun `nested transaction rollback skips conversion even when the outer transaction commits`() {
        val nested = TransactionTemplate(manager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
        }
        transaction.executeWithoutResult {
            proxy().purchase("outer")
            nested.executeWithoutResult { status ->
                proxy().purchase("nested")
                status.setRollbackOnly()
            }
        }
        assertEquals(listOf("outer"), jdbc.queryForList("SELECT user_id FROM orders", String::class.java))
        verify(exactly = 1) { transport.trackConversion("outer", "checkout", "purchase") }
        verify(exactly = 0) { transport.trackConversion("nested", "checkout", "purchase") }
    }

    @Test
    fun `exception diagnostic remains opt in and is sent even when business transaction rolls back`() {
        assertFailsWith<UnsupportedOperationException> {
            transaction.executeWithoutResult { proxy().fail("failed") }
        }
        assertEquals(0, orderCount())
        verifyPurchase(0)
        verify(exactly = 1) { transport.trackConversion("failed", "checkout", "payment_error") }
    }

    open class PurchaseService(private val jdbc: JdbcTemplate) {
        @PrismTrackConversion("checkout", "purchase", trackWhen = TrackCondition.RETURN_TRUE)
        open fun completed(@PrismUserId userId: String): Boolean = true

        @TransactionalEventListener
        @PrismTrackConversion("checkout", "purchase")
        open fun onCommitted(@PrismUserId userId: String) { }

        @Transactional
        @PrismTrackConversion("checkout", "purchase", trackWhen = TrackCondition.RETURN_TRUE)
        open fun purchase(@PrismUserId userId: String): Boolean {
            jdbc.update("INSERT INTO orders VALUES (?)", userId)
            return true
        }

        @Transactional
        @PrismTrackConversion("checkout", "purchase", trackWhen = TrackCondition.RETURN_TRUE)
        open fun rollbackOnly(@PrismUserId userId: String): Boolean {
            jdbc.update("INSERT INTO orders VALUES (?)", userId)
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
            return true
        }

        @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NESTED)
        @PrismTrackConversion("checkout", "purchase", trackWhen = TrackCondition.RETURN_TRUE)
        open fun nestedRollbackOnly(@PrismUserId userId: String): Boolean {
            jdbc.update("INSERT INTO orders VALUES (?)", userId)
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
            return true
        }

        @PrismTrackConversion("checkout", "purchase")
        @PrismTrackConversion("checkout", "payment_error", trackOnException = true)
        open fun fail(@PrismUserId userId: String) {
            jdbc.update("INSERT INTO orders VALUES (?)", userId)
            throw UnsupportedOperationException("Payment failed")
        }
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    class DefaultTransactions

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement(order = -100)
    class OrderedTransactions
}
