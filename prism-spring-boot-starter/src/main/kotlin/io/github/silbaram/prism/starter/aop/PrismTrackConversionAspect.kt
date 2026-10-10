package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismTrackConversion
import io.github.silbaram.prism.starter.annotation.TrackCondition
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Collections
import java.util.IdentityHashMap

/** Uses prior exposure and defers normal-return events until the enclosing transaction commits. */
@Aspect
// Run inside Spring transaction advice, including user-configured higher priority orders.
@Order(Ordered.LOWEST_PRECEDENCE)
class PrismTrackConversionAspect(private val prismExperimentClient: PrismExperimentClient) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Around("@annotation(io.github.silbaram.prism.starter.annotation.PrismTrackConversion) || @annotation(io.github.silbaram.prism.starter.annotation.PrismTrackConversions)")
    fun handleTrackConversion(joinPoint: ProceedingJoinPoint): Any? {
        // Configuration errors surface before business execution; tracking failures remain fail-safe.
        val invocation = PrismMethodMetadata.resolve(joinPoint)
        val result = try {
            joinPoint.proceed()
        } catch (exception: Throwable) {
            invocation.conversions.filter { it.trackOnException && it.trackWhen == TrackCondition.ALWAYS }
                .forEach { track(invocation.userId, it) }
            throw exception
        }
        invocation.conversions.filter { shouldTrack(result, it.trackWhen) }
            .forEach { trackAfterCommit(invocation.userId, it) }
        return result
    }

    private fun trackAfterCommit(userId: String, annotation: PrismTrackConversion) {
        if (PrismTransactionLifecycle.isCommitted() ||
            (!TransactionSynchronizationManager.isActualTransactionActive() && !PrismTransactionLifecycle.isObserved())) {
            track(userId, annotation)
            return
        }
        // Never report success before a transaction whose completion we cannot observe.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            logger.warn("Conversion skipped: transaction synchronization unavailable, experimentKey={}, eventName={}",
                annotation.experimentKey, annotation.eventName)
            return
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                private val laterSavepoints = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
                private var rolledBack = false
                override fun savepoint(savepoint: Any) { laterSavepoints.add(savepoint) }
                override fun savepointRollback(savepoint: Any) {
                    // A savepoint created before this event contains the tracked operation.
                    // Rolling back a later savepoint must not cancel earlier committed work.
                    if (savepoint !in laterSavepoints) rolledBack = true
                }
                override fun afterCommit() { if (!rolledBack) track(userId, annotation) }
            })
        } catch (exception: Exception) {
            logger.warn("Conversion scheduling failed: experimentKey={}, eventName={}, error={}",
                annotation.experimentKey, annotation.eventName, exception.javaClass.simpleName)
        }
    }

    private fun track(userId: String, annotation: PrismTrackConversion) {
        try {
            prismExperimentClient.trackIfAssigned(userId, annotation.experimentKey, annotation.eventName)
        } catch (exception: Exception) {
            logger.warn("Conversion tracking failed: experimentKey={}, eventName={}, error={}",
                annotation.experimentKey, annotation.eventName, exception.javaClass.simpleName)
        }
    }

    private fun shouldTrack(value: Any?, condition: TrackCondition): Boolean = when (condition) {
        TrackCondition.ALWAYS -> true
        TrackCondition.RETURN_TRUE -> value == true
        TrackCondition.RETURN_FALSE -> value == false
        TrackCondition.NOT_NULL -> value != null
        TrackCondition.IS_NULL -> value == null
    }
}
