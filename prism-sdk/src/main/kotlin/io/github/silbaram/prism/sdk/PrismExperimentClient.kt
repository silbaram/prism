package io.github.silbaram.prism.sdk

import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import org.slf4j.LoggerFactory

/**
 * PrismClient를 감싸 안전한 A/B 실험 흐름만 노출하는 래퍼입니다.
 *
 * - assign 시 `assigned` 플래그를 계산해 반환합니다.
 * - trackConversionIfAssigned로 통계 오염을 방지할 수 있습니다.
 *
 * Spring 여부와 무관하게 사용할 수 있습니다.
 */
class PrismExperimentClient(
    private val prismClient: PrismClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun assign(userId: String, experimentKey: String): AssignmentOutcome {
        val response = prismClient.assign(userId, experimentKey)
        return AssignmentOutcome.from(response)
    }

    /**
     * 할당이 성공한 경우에만 전환을 기록합니다.
     *
     * @return true면 전환이 전송됨, false면 스킵
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

    fun trackConversion(userId: String, experimentKey: String, eventName: String) {
        prismClient.trackConversion(userId, experimentKey, eventName)
    }

    private fun mask(userId: String): String {
        return if (userId.length > 4) {
            "${userId.take(2)}***${userId.substring(userId.length - 2)}"
        } else "***"
    }
}

data class AssignmentOutcome(
    val userId: String,
    val experimentKey: String,
    val variant: String?,
    val assigned: Boolean,
    val resultCode: String,
    val resultMessage: String
) {
    companion object {
        fun from(response: AssignmentResponse): AssignmentOutcome {
            val assigned = response.variant != null && response.resultCode == ResponseCode.SUCCESS.code
            return AssignmentOutcome(
                userId = response.userId,
                experimentKey = response.experimentKey,
                variant = response.variant,
                assigned = assigned,
                resultCode = response.resultCode,
                resultMessage = response.resultMessage
            )
        }

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
