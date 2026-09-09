package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismTrackConversion
import io.github.silbaram.prism.starter.annotation.TrackCondition
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory

/** Tracks events after execution using prior exposure, without a ThreadLocal or Aspect ordering contract. */
@Aspect
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
            .forEach { track(invocation.userId, it) }
        return result
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
