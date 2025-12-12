package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.starter.annotation.PrismExperiment
import io.github.silbaram.prism.starter.annotation.PrismUserId
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory

/**
 * @PrismExperiment 어노테이션이 붙은 메소드를 가로채서
 * Prism 서버로부터 variant를 조회하고 컨텍스트에 저장하는 Aspect입니다.
 *
 * 주의: @Component를 사용하지 않습니다. PrismAutoConfiguration에서 @Bean으로 등록합니다.
 */
@Aspect
class PrismExperimentAspect(
    private val prismClient: PrismClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Around("@annotation(prismExperiment)")
    fun handleExperiment(
        joinPoint: ProceedingJoinPoint,
        prismExperiment: PrismExperiment
    ): Any? {
        return try {
            // 1. userId 추출
            val userId = extractUserId(joinPoint, prismExperiment)
                ?: throw IllegalArgumentException(
                    "userId를 찾을 수 없습니다. @PrismUserId 어노테이션을 사용하거나 " +
                    "'${prismExperiment.userIdParam}' 이름의 파라미터를 추가하세요."
                )

            // 2. Prism 서버에서 variant 조회
            val variant = try {
                val response = prismClient.assign(userId, prismExperiment.experimentKey)
                response.variant ?: prismExperiment.defaultVariant
            } catch (e: Exception) {
                logger.warn(
                    "실험 '${prismExperiment.experimentKey}' variant 조회 실패, " +
                    "기본값 '${prismExperiment.defaultVariant}' 사용: ${e.message}"
                )
                prismExperiment.defaultVariant
            }

            // 3. PrismContext에 variant 저장
            PrismContext.setCurrentVariant(variant)

            logger.debug(
                "실험 '${prismExperiment.experimentKey}' - userId: $userId, variant: $variant"
            )

            // 4. 원래 메소드 실행
            joinPoint.proceed()
        } finally {
            // 5. 실행 완료 후 컨텍스트 정리 (메모리 누수 방지)
            PrismContext.clear()
        }
    }

    /**
     * 메소드 파라미터에서 userId를 추출합니다.
     *
     * 우선순위:
     * 1. @PrismUserId 어노테이션이 붙은 파라미터
     * 2. userIdParam 이름과 일치하는 파라미터
     */
    private fun extractUserId(
        joinPoint: ProceedingJoinPoint,
        prismExperiment: PrismExperiment
    ): String? {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method
        val parameterAnnotations = method.parameterAnnotations
        val parameterNames = signature.parameterNames
        val args = joinPoint.args

        // 1. @PrismUserId 어노테이션이 붙은 파라미터 찾기
        parameterAnnotations.forEachIndexed { index, annotations ->
            if (annotations.any { it is PrismUserId }) {
                return args[index]?.toString()
            }
        }

        // 2. userIdParam 이름과 일치하는 파라미터 찾기
        val userIdParamIndex = parameterNames.indexOf(prismExperiment.userIdParam)
        if (userIdParamIndex >= 0 && userIdParamIndex < args.size) {
            return args[userIdParamIndex]?.toString()
        }

        return null
    }
}
