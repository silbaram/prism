package io.github.silbaram.prism.starter.routing

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismVariantMethod
import io.github.silbaram.prism.starter.util.logger
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.stereotype.Component
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * @PrismVariantMethod가 붙은 메서드를 variant에 따라 자동으로 선택하고 실행하는 라우터입니다.
 *
 * 사용 예시:
 * ```kotlin
 * @Service
 * class CheckoutService(
 *     private val router: PrismVariantMethodRouter,
 *     private val conversionTracker: PrismConversionTracker
 * ) {
 *     fun processCheckout(userId: String, amount: Int): Int {
 *         val result = router.route(this, userId, "checkout_discount", amount)
 *         // 필요한 경우 명시적으로 전환 추적
 *         if (result > 0) {
 *             conversionTracker.trackConversionSafe(userId, "checkout_discount", "purchase")
 *         }
 *         return result
 *     }
 *
 *     @PrismVariantMethod(variant = "A", experimentKey = "checkout_discount")
 *     fun processCheckoutA(amount: Int): Int = (amount * 0.9).toInt()
 *
 *     @PrismVariantMethod(variant = "B", experimentKey = "checkout_discount")
 *     fun processCheckoutB(amount: Int): Int = (amount * 0.8).toInt()
 * }
 * ```
 */
@Component
class PrismVariantMethodRouter(
    private val prismExperimentClient: PrismExperimentClient
) {
    private val logger = logger()

    // 메서드 캐시: (클래스 -> experimentKey -> variant -> Method)
    // Thread-safe를 위해 ConcurrentHashMap 사용
    private val methodCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, ConcurrentHashMap<String, Method>>>()

    /**
     * variant에 맞는 메서드를 찾아서 실행합니다.
     *
     * Fail-safe 동작:
     * - variant 메서드를 찾지 못하면 자동으로 "control" 메서드로 폴백합니다.
     * - control 메서드도 없으면 예외를 던집니다.
     *
     * @param instance 메서드를 호출할 인스턴스 (보통 this)
     * @param userId 사용자 ID
     * @param experimentKey 실험 키
     * @param args 메서드에 전달할 인자들
     * @return 실행된 메서드의 반환값
     * @throws NoSuchMethodException control 메서드조차 찾을 수 없는 경우
     */
    fun <T> route(instance: Any, userId: String, experimentKey: String, vararg args: Any?): T {
        val variant = assignVariant(userId, experimentKey)
        val method = resolveMethod(instance.javaClass, experimentKey, variant)
        return executeMethod(method, instance, args)
    }

    /**
     * 사용자에게 variant를 할당합니다.
     */
    private fun assignVariant(userId: String, experimentKey: String): String {
        val outcome = prismExperimentClient.assign(userId, experimentKey)
        val variant = outcome.variant ?: "control"
        logger.debug { "라우팅: experimentKey=$experimentKey, userId=$userId, variant=$variant" }
        return variant
    }

    /**
     * variant에 맞는 메서드를 찾습니다. 찾지 못하면 control로 폴백합니다.
     */
    private fun resolveMethod(clazz: Class<*>, experimentKey: String, variant: String): Method {
        var method = findMethodForVariant(clazz, experimentKey, variant)

        // Fail-safe: variant 메서드를 찾지 못하면 control로 폴백
        if (method == null && variant != "control") {
            logger.warn {
                "Variant method not found, falling back to 'control'. " +
                "variant='$variant', experimentKey=$experimentKey, class=${clazz.simpleName}"
            }
            method = findMethodForVariant(clazz, experimentKey, "control")
        }

        // control도 없으면 예외 발생
        return method ?: throw NoSuchMethodException(
            "variant='$variant'와 'control'에 해당하는 @PrismVariantMethod를 찾을 수 없습니다. " +
                "experimentKey=$experimentKey, class=${clazz.simpleName}"
        )
    }

    /**
     * 메서드를 실행하고 결과를 반환합니다.
     */
    private fun <T> executeMethod(method: Method, instance: Any, args: Array<out Any?>): T {
        return try {
            @Suppress("UNCHECKED_CAST")
            method.invoke(instance, *args) as T
        } catch (e: Exception) {
            logger.error(e) { "메서드 실행 실패: method=${method.name}, class=${instance.javaClass.simpleName}" }
            throw e
        }
    }

    /**
     * userId를 로그에 안전하게 출력하기 위해 마스킹합니다.
     * 예: "user-12345678" -> "us***78"
     */
    private fun maskUserId(userId: String): String {
        return when {
            userId.length <= 4 -> "****"
            else -> "${userId.take(2)}***${userId.takeLast(2)}"
        }
    }

    /**
     * 특정 experimentKey와 variant에 해당하는 메서드를 찾습니다.
     * 메서드는 캐싱되어 재사용됩니다.
     *
     * Thread-Safety: computeIfAbsent는 원자적 연산을 보장하므로,
     * 동시에 여러 스레드가 호출하더라도 scanMethodsForExperiment()는 단 한 번만 실행됩니다.
     */
    private fun findMethodForVariant(clazz: Class<*>, experimentKey: String, variant: String): Method? {
        // 캐시 확인 (Issue 1 수정: getOrPut → computeIfAbsent)
        val classCache = methodCache.computeIfAbsent(clazz) { ConcurrentHashMap() }
        val experimentCache = classCache.computeIfAbsent(experimentKey) {
            // 캐시 미스: 클래스를 스캔해서 해당 experimentKey의 모든 메서드 수집
            scanMethodsForExperiment(clazz, experimentKey)
        }

        return experimentCache[variant]
    }

    /**
     * 클래스에서 특정 experimentKey를 가진 @PrismVariantMethod 메서드를 모두 찾아 맵으로 반환합니다.
     *
     * Proxy-Safe: AnnotationUtils.findAnnotation()은 Spring AOP 프록시 환경에서도
     * 메서드 어노테이션을 안전하게 찾습니다.
     *
     * Performance: 메서드를 캐싱할 때 isAccessible을 true로 설정하여
     * 매 호출마다 접근성 검사를 하지 않도록 최적화합니다.
     */
    private fun scanMethodsForExperiment(clazz: Class<*>, experimentKey: String): ConcurrentHashMap<String, Method> {
        val result = ConcurrentHashMap<String, Method>()

        clazz.declaredMethods.forEach { method ->
            // Issue 2 수정: 프록시 환경에서도 어노테이션을 찾을 수 있도록 AnnotationUtils 사용
            val annotation = AnnotationUtils.findAnnotation(method, PrismVariantMethod::class.java)
            if (annotation != null && annotation.experimentKey == experimentKey) {
                // 캐싱 시점에 한 번만 접근성 설정 (스레드 안전)
                method.isAccessible = true
                result[annotation.variant] = method
                logger.debug {
                    "메서드 등록: class=${clazz.simpleName}, experimentKey=$experimentKey, " +
                        "variant=${annotation.variant}, method=${method.name}"
                }
            }
        }

        return result
    }
}
