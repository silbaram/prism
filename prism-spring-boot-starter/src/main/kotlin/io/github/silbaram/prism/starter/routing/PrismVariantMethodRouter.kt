package io.github.silbaram.prism.starter.routing

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismVariantMethod
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(javaClass)

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
        // 1. variant 할당
        val outcome = prismExperimentClient.assign(userId, experimentKey)
        val variant = outcome.variant ?: "control"

        logger.debug("라우팅: experimentKey=$experimentKey, userId=$userId, variant=$variant")

        // 2. 해당 variant의 메서드 찾기
        var method = findMethodForVariant(instance.javaClass, experimentKey, variant)

        // 3. 메서드를 찾지 못하면 control로 폴백 (Fail-safe)
        if (method == null && variant != "control") {
            logger.warn(
                "Variant method not found, falling back to 'control'. " +
                "variant='{}', experimentKey={}, class={}, userId={}",
                variant, experimentKey, instance.javaClass.simpleName, maskUserId(userId)
            )
            method = findMethodForVariant(instance.javaClass, experimentKey, "control")
        }

        // 4. control도 없으면 예외 발생
        if (method == null) {
            throw NoSuchMethodException(
                "variant='$variant'와 'control'에 해당하는 @PrismVariantMethod를 찾을 수 없습니다. " +
                    "experimentKey=$experimentKey, class=${instance.javaClass.simpleName}"
            )
        }

        // 5. 메서드 실행
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
     */
    private fun findMethodForVariant(clazz: Class<*>, experimentKey: String, variant: String): Method? {
        // 캐시 확인
        val classCache = methodCache.getOrPut(clazz) { ConcurrentHashMap() }
        val experimentCache = classCache.getOrPut(experimentKey) {
            // 캐시 미스: 클래스를 스캔해서 해당 experimentKey의 모든 메서드 수집
            scanMethodsForExperiment(clazz, experimentKey)
        }

        return experimentCache[variant]
    }

    /**
     * 클래스에서 특정 experimentKey를 가진 @PrismVariantMethod 메서드를 모두 찾아 맵으로 반환합니다.
     */
    private fun scanMethodsForExperiment(clazz: Class<*>, experimentKey: String): ConcurrentHashMap<String, Method> {
        val result = ConcurrentHashMap<String, Method>()

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
