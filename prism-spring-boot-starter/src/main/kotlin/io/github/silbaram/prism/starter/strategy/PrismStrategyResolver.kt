package io.github.silbaram.prism.starter.strategy

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.starter.annotation.PrismStrategy
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * reified 타입 파라미터를 사용하기 위한 extension function
 */
inline fun <reified T : Any> PrismStrategyResolver.resolve(userId: String, experimentKey: String): T {
    return this.resolve(T::class.java, userId, experimentKey)
}

/**
 * @PrismStrategy가 붙은 전략 구현체를 variant에 따라 자동으로 선택하는 Resolver입니다.
 *
 * 사용 예시:
 * ```kotlin
 * @Service
 * class CheckoutService(
 *     private val strategyResolver: PrismStrategyResolver,
 *     private val conversionTracker: PrismConversionTracker
 * ) {
 *     fun processCheckout(userId: String, amount: Int): Int {
 *         val strategy = strategyResolver.resolve<CheckoutStrategy>(userId, "checkout_discount")
 *         val result = strategy.calculatePrice(amount)
 *         // 필요한 경우 명시적으로 전환 추적
 *         if (result > 0) {
 *             conversionTracker.trackConversionSafe(userId, "checkout_discount", "purchase")
 *         }
 *         return result
 *     }
 * }
 * ```
 */
@Component
class PrismStrategyResolver(
    private val applicationContext: ApplicationContext,
    private val prismExperimentClient: PrismExperimentClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // 전략 캐시: (인터페이스 타입 -> experimentKey -> variant -> Bean)
    // Thread-safe를 위해 ConcurrentHashMap 사용
    private val strategyCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, ConcurrentHashMap<String, Any>>>()


    /**
     * userId와 experimentKey를 기반으로 적절한 전략 구현체를 반환합니다.
     *
     * @param strategyInterface 전략 인터페이스 타입
     * @param userId 사용자 ID
     * @param experimentKey 실험 키
     * @return 현재 variant에 맞는 전략 구현체
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(strategyInterface: Class<T>, userId: String, experimentKey: String): T {
        // 1. variant 할당
        val outcome = prismExperimentClient.assign(userId, experimentKey)
        val variant = outcome.variant ?: "control"

        logger.debug("전략 선택: experimentKey=$experimentKey, userId=$userId, variant=$variant")

        // 2. 해당 variant의 전략 찾기
        val strategy = findStrategyForVariant(strategyInterface, experimentKey, variant)
            ?: throw IllegalStateException(
                "variant='$variant'에 해당하는 @PrismStrategy를 찾을 수 없습니다. " +
                    "experimentKey=$experimentKey, interface=${strategyInterface.simpleName}"
            )

        return strategy as T
    }

    /**
     * 특정 experimentKey와 variant에 해당하는 전략 Bean을 찾습니다.
     * 전략은 캐싱되어 재사용됩니다.
     */
    private fun findStrategyForVariant(
        strategyInterface: Class<*>,
        experimentKey: String,
        variant: String
    ): Any? {
        // 캐시 확인
        val interfaceCache = strategyCache.getOrPut(strategyInterface) { ConcurrentHashMap() }
        val experimentCache = interfaceCache.getOrPut(experimentKey) {
            // 캐시 미스: ApplicationContext를 스캔해서 해당 experimentKey의 모든 전략 수집
            scanStrategiesForExperiment(strategyInterface, experimentKey)
        }

        return experimentCache[variant]
    }

    /**
     * ApplicationContext에서 특정 인터페이스와 experimentKey를 가진 @PrismStrategy Bean을 모두 찾아 맵으로 반환합니다.
     */
    private fun scanStrategiesForExperiment(
        strategyInterface: Class<*>,
        experimentKey: String
    ): ConcurrentHashMap<String, Any> {
        val result = ConcurrentHashMap<String, Any>()

        // ApplicationContext에서 해당 인터페이스 타입의 모든 Bean 찾기
        val beans = applicationContext.getBeansOfType(strategyInterface)

        beans.values.forEach { bean ->
            val annotation = bean.javaClass.getAnnotation(PrismStrategy::class.java)
            if (annotation != null && annotation.experimentKey == experimentKey) {
                result[annotation.variant] = bean
                logger.debug(
                    "전략 등록: interface=${strategyInterface.simpleName}, experimentKey=$experimentKey, " +
                        "variant=${annotation.variant}, class=${bean.javaClass.simpleName}"
                )
            }
        }

        return result
    }
}
