package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.sdk.AssignmentOutcome
import io.github.silbaram.prism.sdk.PrismExperimentClient
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
    private val prismExperimentClient: PrismExperimentClient
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
            val outcome = try {
                prismExperimentClient.assign(userId, prismExperiment.experimentKey)
            } catch (e: Exception) {
                // 예외 발생 시 안전하게 실패 처리
                logger.warn(
                    "실험 '${prismExperiment.experimentKey}' variant 조회 실패: ${e.message}"
                )
                AssignmentOutcome.failed(
                    userId = userId,
                    experimentKey = prismExperiment.experimentKey,
                    message = e.message ?: "Unknown error"
                )
            }

            if (!outcome.assigned) {
                logger.warn(
                    "실험 '${prismExperiment.experimentKey}' 할당 실패 (resultCode=${outcome.resultCode}, message=${outcome.resultMessage})"
                )
            }

            val variant = if (outcome.assigned) outcome.variant else null
            val wasActualAssignment = outcome.assigned

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
