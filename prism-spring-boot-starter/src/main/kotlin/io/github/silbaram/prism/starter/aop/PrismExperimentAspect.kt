package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect

/** Records exposure before execution. Access variants explicitly through PrismExperimentClient or strategies. */
@Aspect
class PrismExperimentAspect(private val prismExperimentClient: PrismExperimentClient) {
    @Around("@annotation(prismExperiment)")
    fun handleExperiment(joinPoint: ProceedingJoinPoint, prismExperiment: PrismExperiment): Any? {
        val invocation = PrismMethodMetadata.resolve(joinPoint)
        prismExperimentClient.assign(invocation.userId, prismExperiment.experimentKey)
        return joinPoint.proceed()
    }
}
