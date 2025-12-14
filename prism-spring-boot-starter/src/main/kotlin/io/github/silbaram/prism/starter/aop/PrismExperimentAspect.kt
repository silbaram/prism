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
                ?: run {
                    // userId가 없으면 실험 할당을 건너뛰고 원본 로직을 그대로 실행
                    logger.warn(
                        "userId를 찾을 수 없습니다. @PrismUserId 또는 '${prismExperiment.userIdParam}' 파라미터를 확인하세요. " +
                        "method=${joinPoint.signature}"
                    )
                    PrismContext.setCurrentVariant(null, false)
                    return joinPoint.proceed()
                }

            // 2. Prism 서버에서 variant 조회 및 할당 성공 여부 확인
            val (variant: String?, wasActualAssignment: Boolean) = try {
                val response = prismClient.assign(userId, prismExperiment.experimentKey)

                // 성공 조건: variant가 null이 아니고, resultCode가 "0000" (SUCCESS)
                if (response.variant != null && response.resultCode == "0000") {
                    // 실제로 서버에서 할당받음 → 통계에 포함되어야 함
                    Pair(response.variant!!, true)
                } else {
                    // API 호출은 성공했지만 실험이 없거나 비활성화 상태
                    logger.warn(
                        "실험 '${prismExperiment.experimentKey}' 할당 실패 (resultCode=${response.resultCode})"
                    )
                    Pair(null, false)
                }
            } catch (e: Exception) {
                // 네트워크 오류 등으로 API 호출 실패
                logger.warn(
                    "실험 '${prismExperiment.experimentKey}' variant 조회 실패: ${e.message}"
                )
                Pair(null, false)
            }

            // 3. PrismContext에 variant 및 할당 성공 여부 저장
            PrismContext.setCurrentVariant(variant, wasActualAssignment)

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
