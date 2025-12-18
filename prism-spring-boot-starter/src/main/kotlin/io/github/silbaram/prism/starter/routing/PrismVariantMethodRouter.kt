package io.github.silbaram.prism.starter.routing

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismVariantMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.lang.reflect.Method

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
    private val logger = LoggerFactory.getLogger(javaClass)

    // 메서드 캐시: (클래스 -> experimentKey -> variant -> Method)
    private val methodCache = mutableMapOf<Class<*>, MutableMap<String, MutableMap<String, Method>>>()

    /**
     * variant에 맞는 메서드를 찾아서 실행합니다.
     *
     * @param instance 메서드를 호출할 인스턴스 (보통 this)
     * @param userId 사용자 ID
     * @param experimentKey 실험 키
     * @param args 메서드에 전달할 인자들
     * @return 실행된 메서드의 반환값
     * @throws NoSuchMethodException 해당 variant의 메서드를 찾을 수 없는 경우
     */
    fun <T> route(instance: Any, userId: String, experimentKey: String, vararg args: Any?): T {
        // 1. variant 할당
        val outcome = prismExperimentClient.assign(userId, experimentKey)
        val variant = outcome.variant ?: "control"

        logger.debug("라우팅: experimentKey=$experimentKey, userId=$userId, variant=$variant")

        // 2. 해당 variant의 메서드 찾기
        val method = findMethodForVariant(instance.javaClass, experimentKey, variant)
            ?: throw NoSuchMethodException(
                "variant='$variant'에 해당하는 @PrismVariantMethod를 찾을 수 없습니다. " +
                    "experimentKey=$experimentKey, class=${instance.javaClass.simpleName}"
            )

        // 3. 메서드 실행
        return try {
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            method.invoke(instance, *args) as T
        } catch (e: Exception) {
            logger.error("메서드 실행 실패: method=${method.name}, experimentKey=$experimentKey, variant=$variant", e)
            throw e
        }
    }

    /**
     * 특정 experimentKey와 variant에 해당하는 메서드를 찾습니다.
     * 메서드는 캐싱되어 재사용됩니다.
     */
    private fun findMethodForVariant(clazz: Class<*>, experimentKey: String, variant: String): Method? {
        // 캐시 확인
        val classCache = methodCache.getOrPut(clazz) { mutableMapOf() }
        val experimentCache = classCache.getOrPut(experimentKey) {
            // 캐시 미스: 클래스를 스캔해서 해당 experimentKey의 모든 메서드 수집
            scanMethodsForExperiment(clazz, experimentKey)
        }

        return experimentCache[variant]
    }

    /**
     * 클래스에서 특정 experimentKey를 가진 @PrismVariantMethod 메서드를 모두 찾아 맵으로 반환합니다.
     */
    private fun scanMethodsForExperiment(clazz: Class<*>, experimentKey: String): MutableMap<String, Method> {
        val result = mutableMapOf<String, Method>()

        clazz.declaredMethods.forEach { method ->
            val annotation = method.getAnnotation(PrismVariantMethod::class.java)
            if (annotation != null && annotation.experimentKey == experimentKey) {
                result[annotation.variant] = method
                logger.debug(
                    "메서드 등록: class=${clazz.simpleName}, experimentKey=$experimentKey, " +
                        "variant=${annotation.variant}, method=${method.name}"
                )
            }
        }

        return result
    }
}
