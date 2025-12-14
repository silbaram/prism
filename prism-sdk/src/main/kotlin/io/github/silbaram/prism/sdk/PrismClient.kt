package io.github.silbaram.prism.sdk

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionRequest
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Prism A/B 테스트 플랫폼의 Java/Kotlin 클라이언트 SDK.
 *
 * 이 클라이언트는 "안전 우선(Fail-safe)" 방식으로 설계되었습니다:
 * - API 호출 실패 시 예외를 던지지 않고 에러 응답을 반환합니다.
 * - A/B 테스트 실패가 메인 비즈니스 로직을 중단시키지 않습니다.
 * - 모든 에러는 로그에 기록됩니다.
 */
class PrismClient(
    private val baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(5)
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    private val objectMapper = jacksonObjectMapper()

    companion object {
        private val logger = LoggerFactory.getLogger(PrismClient::class.java)
    }

    /**
     * 사용자를 A/B 테스트 변형(variant)에 할당합니다.
     *
     * **실패 처리:**
     * - API 호출 실패 시: resultCode가 "9999"이고 variant가 null인 응답 반환
     * - 네트워크 오류 시: resultCode가 "9999"이고 variant가 null인 응답 반환
     * - 모든 에러는 로그에 기록됨
     *
     * **사용 예시:**
     * ```kotlin
     * val response = prismClient.assign("user-123", "exp-1")
     * val variant = response.variant ?: "control"  // null이면 기본값 사용
     * ```
     *
     * @param userId 사용자 고유 식별자
     * @param experimentKey 실험 키
     * @return 할당 결과 (실패 시 variant가 null인 응답)
     */
    fun assign(userId: String, experimentKey: String): AssignmentResponse {
        return try {
            val uri = URI.create("$baseUrl/v1/assign?userId=$userId&experimentKey=$experimentKey")
            val request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                objectMapper.readValue(response.body())
            } else {
                logger.warn("Failed to assign variant: HTTP ${response.statusCode()} - ${response.body()}")
                createErrorResponse(userId, experimentKey, "HTTP ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error("Error assigning variant for userId=$userId, experimentKey=$experimentKey", e)
            createErrorResponse(userId, experimentKey, e.message ?: "Unknown error")
        }
    }

    /**
     * 전환 이벤트를 추적합니다 (fire-and-forget).
     *
     * **실패 처리:**
     * - 실패 시 로그만 기록하고 조용히 실패 (메인 로직에 영향 없음)
     * - 예외를 던지지 않음
     * - 반환값 없음
     *
     * **사용 예시:**
     * ```kotlin
     * prismClient.trackConversion("user-123", "exp-1", "purchase")
     * // 실패해도 앱 동작에 영향 없음
     * ```
     *
     * @param userId 사용자 고유 식별자
     * @param experimentKey 실험 키
     * @param eventName 이벤트 이름 (예: "purchase", "signup")
     */
    fun trackConversion(userId: String, experimentKey: String, eventName: String) {
        try {
            val uri = URI.create("$baseUrl/v1/conversions")
            val payload = ConversionRequest(
                experimentKey = experimentKey,
                userId = userId,
                eventName = eventName
            )
            val jsonBody = objectMapper.writeValueAsString(payload)

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                logger.warn("Failed to track conversion: HTTP ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error("Error tracking conversion for userId=$userId, experimentKey=$experimentKey, eventName=$eventName", e)
            // 조용히 실패 (예외 던지지 않음)
        }
    }

    /**
     * 에러 응답을 생성하는 내부 헬퍼 메서드.
     *
     * @param userId 사용자 ID
     * @param experimentKey 실험 키
     * @param errorMessage 에러 메시지
     * @return 에러 응답 (variant가 null)
     */
    private fun createErrorResponse(
        userId: String,
        experimentKey: String,
        errorMessage: String
    ): AssignmentResponse {
        return AssignmentResponse(
            userId = userId,
            experimentKey = experimentKey,
            variant = null,
            resultCode = "9999",
            resultMessage = "Assignment failed: $errorMessage"
        )
    }
}
