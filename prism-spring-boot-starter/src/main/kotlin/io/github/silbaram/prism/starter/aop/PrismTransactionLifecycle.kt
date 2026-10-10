package io.github.silbaram.prism.starter.aop

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered
import org.springframework.transaction.ConfigurableTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Distinguishes a live transaction from its committed callbacks, which still retain resources. */
internal object PrismTransactionLifecycle {
    private val resourceKey = Any()
    private fun bound(): Completion? {
        val completion = TransactionSynchronizationManager.getResource(resourceKey) as? Completion ?: return null
        if (!completion.transaction.isCompleted) return completion
        // A caller's execution listener can throw before our final cleanup listener runs.
        // Retire that completed scope before the next call/transaction can inherit it.
        TransactionSynchronizationManager.unbindResource(resourceKey)
        var previous = completion.previous
        while (previous != null && previous.transaction.isCompleted) previous = previous.previous
        previous?.let { TransactionSynchronizationManager.bindResource(resourceKey, it) }
        return previous
    }

    private fun current(): Completion? {
        val completion = bound() ?: return null
        // A REQUIRES_NEW transaction opened inside afterCompletion has its own synchronization
        // even when Spring cannot suspend the already-completed parent's synchronizations.
        if (TransactionSynchronizationManager.isSynchronizationActive() &&
            TransactionSynchronizationManager.getSynchronizations().none { it === completion }) return null
        return completion
    }

    fun isCommitted(): Boolean = current()?.committed == true
    fun isObserved(): Boolean = current() != null

    private fun afterBegin(transaction: TransactionExecution, beginFailure: Throwable?) {
        if (beginFailure != null || !transaction.isNewTransaction) return
        val completion = Completion(transaction, bound())
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(completion)
        TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey)
        TransactionSynchronizationManager.bindResource(resourceKey, completion)
    }

    private val beginListener = object : TransactionExecutionListener {
        override fun afterBegin(transaction: TransactionExecution, beginFailure: Throwable?) {
            PrismTransactionLifecycle.afterBegin(transaction, beginFailure)
        }
    }
    private val completionListener = object : TransactionExecutionListener {
        override fun afterCommit(transaction: TransactionExecution, commitFailure: Throwable?) { cleanup(transaction) }
        override fun afterRollback(transaction: TransactionExecution, rollbackFailure: Throwable?) { cleanup(transaction) }
    }

    fun observe(manager: ConfigurableTransactionManager) {
        val listeners = manager.transactionExecutionListeners
        if (listeners.none { it === beginListener }) {
            // Install the phase marker before user synchronizations, and remove it after
            // both synchronization callbacks and the caller's execution listeners finish.
            manager.setTransactionExecutionListeners(listOf(beginListener) + listeners + completionListener)
        }
    }

    private fun cleanup(transaction: TransactionExecution) {
        val completion = bound()?.takeIf { it.transaction === transaction } ?: return
        TransactionSynchronizationManager.unbindResource(resourceKey)
        completion.previous?.let { TransactionSynchronizationManager.bindResource(resourceKey, it) }
    }

    private class Completion(val transaction: TransactionExecution, val previous: Completion?) : TransactionSynchronization {
        var committed = false
        override fun getOrder() = Ordered.HIGHEST_PRECEDENCE
        override fun afterCommit() { committed = true }
        override fun afterCompletion(status: Int) { committed = status == TransactionSynchronization.STATUS_COMMITTED }
        override fun suspend() {
            if (bound() === this) TransactionSynchronizationManager.unbindResource(resourceKey)
        }
        override fun resume() { TransactionSynchronizationManager.bindResource(resourceKey, this) }
    }
}

/** Keeps existing transaction listeners and instruments imperative Spring-managed managers. */
internal class PrismTransactionLifecyclePostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean is PlatformTransactionManager && bean is ConfigurableTransactionManager) {
            PrismTransactionLifecycle.observe(bean)
        }
        return bean
    }
}
