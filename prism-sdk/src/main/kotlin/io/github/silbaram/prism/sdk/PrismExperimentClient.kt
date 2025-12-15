package io.github.silbaram.prism.sdk

import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import org.slf4j.LoggerFactory

/**
 * A/B 테스트를 안전하게 실행할 수 있도록 도와주는 클라이언트입니다.
 *
 * 주요 기능:
 * - 사용자에게 실험 variant 할당 (성공/실패 여부 확인 가능)
 * - 할당에 성공한 경우에만 전환 이벤트 기록 (통계 오염 방지)
 *
 * Spring Boot 환경이 아니어도 사용 가능합니다.
 */
class PrismExperimentClient(
    private val prismClient: PrismClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 사용자에게 실험 variant를 할당합니다.
     *
     * API 장애나 네트워크 오류가 발생해도 예외를 던지지 않고,
     * assigned=false인 결과를 반환합니다.
     *
     * @param userId 사용자 ID
     * @param experimentKey 실험 키
     * @return 할당 결과 (variant, assigned 플래그 포함)
     */
    fun assign(userId: String, experimentKey: String): AssignmentOutcome {
        return try {
            val response = prismClient.assign(userId, experimentKey)
            AssignmentOutcome.from(response)
        } catch (e: Exception) {
            logger.error("할당 실패: userId=${mask(userId)}, experimentKey=$experimentKey", e)
            AssignmentOutcome.failed(userId, experimentKey, e.message ?: "Unknown error")
        }
    }

    /**
     * 전환 이벤트를 추적합니다. (할당 성공 시에만 기록)
     *
     * assign() 호출이 성공했을 때만 전환을 기록하여 통계 오염을 방지합니다.
     * API 장애나 실험 미등록 등으로 할당에 실패했다면 전환을 기록하지 않습니다.
     *
     * 사용 예시:
     * ```
     * val outcome = experimentClient.assign("user-123", "checkout-experiment")
     * // ... 비즈니스 로직 실행 ...
     * experimentClient.trackConversionIfAssigned(outcome, "purchase")
     * ```
     *
     * @param outcome assign() 호출 결과
     * @param eventName 전환 이벤트 이름 (예: "purchase", "signup", "click")
     * @return true: 전환 기록됨, false: 스킵됨 (할당 실패)
     */
    fun trackConversionIfAssigned(outcome: AssignmentOutcome, eventName: String): Boolean {
        return if (outcome.assigned) {
            prismClient.trackConversion(outcome.userId, outcome.experimentKey, eventName)
            true
        } else {
            logger.debug(
                "trackConversion 스킵 (할당 실패): userId=${mask(outcome.userId)}, experimentKey=${outcome.experimentKey}, eventName=$eventName"
            )
            false
        }
    }

    /**
     * 전환 이벤트를 추적합니다. (짧은 버전)
     *
     * trackConversionIfAssigned()와 동일하게 동작하지만 이름이 더 짧습니다.
     *
     * 사용 예시:
     * ```
     * val outcome = experimentClient.assign("user-123", "checkout-experiment")
     * // ... 비즈니스 로직 실행 ...
     * experimentClient.track(outcome, "purchase")  // 간단!
     * ```
     *
     * @param outcome assign() 호출 결과
     * @param eventName 전환 이벤트 이름 (예: "purchase", "signup", "click")
     * @return true: 전환 기록됨, false: 스킵됨 (할당 실패)
     */
    fun track(outcome: AssignmentOutcome, eventName: String): Boolean =
        trackConversionIfAssigned(outcome, eventName)

    private fun mask(userId: String): String {
        return when {
            userId.isBlank() -> "***"
            userId.length <= 4 -> "***"
            else -> "${userId.take(2)}***${userId.takeLast(2)}"
        }
    }
}

/**
 * 실험 할당 결과를 담는 데이터 클래스입니다.
 *
 * @property userId 사용자 ID
 * @property experimentKey 실험 키
 * @property variant 할당된 variant (실패 시 null)
 * @property assigned 할당 성공 여부 (true: 성공, false: 실패)
 * @property resultCode 결과 코드 ("0000": 성공, 그 외: 실패 사유)
 * @property resultMessage 결과 메시지
 */
data class AssignmentOutcome(
    val userId: String,
    val experimentKey: String,
    val variant: String?,
    val assigned: Boolean,
    val resultCode: String,
    val resultMessage: String
) {
    companion object {
        /**
         * API 응답으로부터 AssignmentOutcome을 생성합니다.
         * variant가 있고 resultCode가 성공이면 assigned=true로 설정됩니다.
         */
        fun from(response: AssignmentResponse): AssignmentOutcome {
            val assigned = !response.variant.isNullOrBlank() && response.resultCode == ResponseCode.SUCCESS.code
            return AssignmentOutcome(
                userId = response.userId,
                experimentKey = response.experimentKey,
                variant = response.variant,
                assigned = assigned,
                resultCode = response.resultCode,
                resultMessage = response.resultMessage
            )
        }

        /**
         * 실패한 AssignmentOutcome을 생성합니다.
         * 네트워크 오류나 예외 발생 시 사용됩니다.
         */
        fun failed(userId: String, experimentKey: String, message: String): AssignmentOutcome {
            return AssignmentOutcome(
                userId = userId,
                experimentKey = experimentKey,
                variant = null,
                assigned = false,
                resultCode = ResponseCode.GENERAL_ERROR.code,
                resultMessage = message
            )
        }
    }
}

/**
 * 전환 이벤트를 추적합니다. (AssignmentOutcome에서 직접 호출)
 *
 * outcome 객체에서 바로 track()을 호출할 수 있어 더 간결합니다.
 *
 * 사용 예시:
 * ```
 * val outcome = experimentClient.assign("user-123", "checkout-experiment")
 * // ... 비즈니스 로직 실행 ...
 * outcome.track(experimentClient, "purchase")  // outcome에서 직접!
 * ```
 *
 * @param client PrismExperimentClient 인스턴스
 * @param eventName 전환 이벤트 이름 (예: "purchase", "signup", "click")
 * @return true: 전환 기록됨, false: 스킵됨 (할당 실패)
 */
fun AssignmentOutcome.track(client: PrismExperimentClient, eventName: String): Boolean =
    client.trackConversionIfAssigned(this, eventName)
