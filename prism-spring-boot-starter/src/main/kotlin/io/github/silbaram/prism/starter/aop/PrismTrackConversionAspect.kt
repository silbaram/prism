package io.github.silbaram.prism.starter.aop

import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.starter.annotation.PrismTrackConversion
import io.github.silbaram.prism.starter.annotation.PrismTrackConversions
import io.github.silbaram.prism.starter.annotation.PrismUserId
import io.github.silbaram.prism.starter.annotation.TrackCondition
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory

/**
 * @PrismTrackConversion 어노테이션이 붙은 메소드를 가로채서
 * 메소드 실행 후 자동으로 전환 이벤트를 추적하는 Aspect입니다.
 *
 * **동작 방식:**
 * 1. 메소드를 정상적으로 실행
 * 2. 실행 성공 시, PrismContext.wasActuallyAssigned()를 체크
 * 3. 실제로 할당받은 경우에만 전환 API 호출 (통계 오염 방지)
 * 4. trackOnException=true인 경우 예외 발생 시에도 전환 추적
 * 5. 여러 @PrismTrackConversion 어노테이션이 있으면 모두 처리
 *
 * 주의: @Component를 사용하지 않습니다. PrismAutoConfiguration에서 @Bean으로 등록합니다.
 */
@Aspect
class PrismTrackConversionAspect(
    private val prismClient: PrismClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Around("@annotation(io.github.silbaram.prism.starter.annotation.PrismTrackConversion) || @annotation(io.github.silbaram.prism.starter.annotation.PrismTrackConversions)")
    fun handleTrackConversion(joinPoint: ProceedingJoinPoint): Any? {
        var exceptionOccurred = false
        var thrownException: Throwable? = null
        var returnValue: Any? = null

        return try {
            // 1. 원래 메소드 실행 및 반환값 저장
            joinPoint.proceed().also { returnValue = it }
        } catch (e: Throwable) {
            // 2. 예외 발생 시 기록
            exceptionOccurred = true
            thrownException = e
            throw e
        } finally {
            // 3. 여러 @PrismTrackConversion 어노테이션 수집
            val annotations = collectAnnotations(joinPoint)

            // 4. 각 어노테이션에 대해 전환 추적
            annotations.forEach { annotation ->
                try {
                    val shouldTrack = !exceptionOccurred || annotation.trackOnException

                    if (shouldTrack) {
                        trackConversionIfAssigned(joinPoint, annotation, returnValue)
                    } else {
                        logger.debug(
                            "전환 추적 스킵 (예외 발생): experimentKey=${annotation.experimentKey}, " +
                            "eventName=${annotation.eventName}, exception=${thrownException?.javaClass?.simpleName}"
                        )
                    }
                } catch (trackingException: Exception) {
                    // 전환 추적 실패는 비즈니스 로직에 영향을 주지 않도록 로그만 기록
                    logger.error(
                        "전환 추적 중 오류 발생: experimentKey=${annotation.experimentKey}, " +
                        "eventName=${annotation.eventName}",
                        trackingException
                    )
                }
            }
        }
    }

    /**
     * 메서드에서 모든 @PrismTrackConversion 어노테이션을 수집합니다.
     */
    private fun collectAnnotations(joinPoint: ProceedingJoinPoint): List<PrismTrackConversion> {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method
        val annotations = mutableListOf<PrismTrackConversion>()

        // 여러 어노테이션 (Repeatable인 경우) - 먼저 확인
        method.getAnnotation(PrismTrackConversions::class.java)?.let {
            annotations.addAll(it.value)
            return annotations  // 여러 개가 있으면 바로 리턴
        }

        // 단일 어노테이션
        method.getAnnotation(PrismTrackConversion::class.java)?.let {
            annotations.add(it)
        }

        return annotations
    }

    /**
     * PrismContext에 할당 정보가 있는 경우에만 전환을 추적합니다.
     * trackWhen 조건을 확인하여 반환값에 따라 추적 여부를 결정합니다.
     */
    private fun trackConversionIfAssigned(
        joinPoint: ProceedingJoinPoint,
        prismTrackConversion: PrismTrackConversion,
        returnValue: Any?
    ) {
        // 할당 여부 확인
        if (!PrismContext.wasActuallyAssigned()) {
            logger.debug(
                "전환 추적 스킵 (할당 안 됨): experimentKey=${prismTrackConversion.experimentKey}, " +
                "eventName=${prismTrackConversion.eventName}"
            )
            return
        }

        // trackWhen 조건 확인
        if (!shouldTrackBasedOnReturnValue(returnValue, prismTrackConversion.trackWhen)) {
            logger.debug(
                "전환 추적 스킵 (조건 불일치): experimentKey=${prismTrackConversion.experimentKey}, " +
                "eventName=${prismTrackConversion.eventName}, trackWhen=${prismTrackConversion.trackWhen}, " +
                "returnValue=$returnValue"
            )
            return
        }

        // userId 추출
        val userId = extractUserId(joinPoint, prismTrackConversion)
        if (userId == null) {
            logger.warn(
                "userId를 찾을 수 없어 전환 추적 스킵: @PrismUserId 또는 '${prismTrackConversion.userIdParam}' 파라미터를 확인하세요. " +
                "method=${joinPoint.signature}"
            )
            return
        }

        // 전환 추적
        prismClient.trackConversion(
            userId = userId,
            experimentKey = prismTrackConversion.experimentKey,
            eventName = prismTrackConversion.eventName
        )

        logger.debug(
            "전환 추적 성공: userId=$userId, experimentKey=${prismTrackConversion.experimentKey}, " +
            "eventName=${prismTrackConversion.eventName}, returnValue=$returnValue"
        )
    }

    /**
     * 반환값과 TrackCondition을 기반으로 전환 추적 여부를 결정합니다.
     */
    private fun shouldTrackBasedOnReturnValue(returnValue: Any?, trackWhen: TrackCondition): Boolean {
        return when (trackWhen) {
            TrackCondition.ALWAYS -> true
            TrackCondition.RETURN_TRUE -> returnValue is Boolean && returnValue == true
            TrackCondition.RETURN_FALSE -> returnValue is Boolean && returnValue == false
            TrackCondition.NOT_NULL -> returnValue != null
            TrackCondition.IS_NULL -> returnValue == null
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
        prismTrackConversion: PrismTrackConversion
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
        val userIdParamIndex = parameterNames.indexOf(prismTrackConversion.userIdParam)
        if (userIdParamIndex >= 0 && userIdParamIndex < args.size) {
            return args[userIdParamIndex]?.toString()
        }

        return null
    }
}
