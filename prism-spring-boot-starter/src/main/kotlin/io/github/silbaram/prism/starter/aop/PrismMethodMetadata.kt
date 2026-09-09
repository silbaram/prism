package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.starter.annotation.PrismTrackConversion
import io.github.silbaram.prism.starter.annotation.PrismUserId
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.aop.support.AopUtils
import org.springframework.core.BridgeMethodResolver
import org.springframework.util.ClassUtils
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Resolve parameter annotations across the target hierarchy for both JDK and class proxies. */
internal object PrismMethodMetadata {
    private data class MethodKey(val signature: Method, val implementation: Method, val targetClass: Class<*>)
    private data class Metadata(val userIdIndex: Int, val conversions: List<PrismTrackConversion>)
    private val cache = ConcurrentHashMap<MethodKey, Metadata>()

    fun resolve(joinPoint: ProceedingJoinPoint): Invocation {
        val method = (joinPoint.signature as MethodSignature).method
        val targetClass = joinPoint.target?.javaClass ?: method.declaringClass
        val implementation = implementationOf(method, targetClass)
        val metadata = cache.computeIfAbsent(MethodKey(method, implementation, targetClass)) {
            val hierarchy = generateSequence(targetClass) { it.superclass }.toSet() +
                ClassUtils.getAllInterfacesForClassAsSet(targetClass)
            val declarations = hierarchy.flatMap { it.declaredMethods.toList() }
                .filter { it.name == implementation.name && implementationOf(it, targetClass) == implementation }
            val indexes = (declarations + method + implementation).distinct().flatMap { candidate ->
                candidate.parameterAnnotations.mapIndexedNotNull { index, annotations ->
                    index.takeIf { annotations.any { it is PrismUserId } }
                }
            }.distinct()
            require(indexes.size == 1) { "Exactly one @PrismUserId parameter is required: ${implementation.toGenericString()}" }
            val annotations = implementation.getAnnotationsByType(PrismTrackConversion::class.java).toList()
                .ifEmpty { method.getAnnotationsByType(PrismTrackConversion::class.java).toList() }
            Metadata(indexes.single(), annotations)
        }
        val userId = joinPoint.args[metadata.userIdIndex]?.toString()
        require(!userId.isNullOrBlank()) { "@PrismUserId must not be null or blank: ${implementation.toGenericString()}" }
        return Invocation(userId, metadata.conversions)
    }

    private fun implementationOf(method: Method, targetClass: Class<*>): Method =
        BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, targetClass))

    data class Invocation(val userId: String, val conversions: List<PrismTrackConversion>)
}
