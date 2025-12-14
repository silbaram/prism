package io.github.silbaram.prism.sdk

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionRequest
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
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

        // HTTP 상태 코드
        private const val HTTP_OK = 200

        // 응답 코드
        private const val SUCCESS_CODE = "0000"
        private const val ERROR_CODE = "9999"
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
        val maskedUserId = maskUserId(userId)

        return try {
            // URL 파라미터 인코딩 (특수문자 대응)
            val encodedUserId = URLEncoder.encode(userId, StandardCharsets.UTF_8)
            val encodedExperimentKey = URLEncoder.encode(experimentKey, StandardCharsets.UTF_8)
            val uri = URI.create("$baseUrl/v1/assign?userId=$encodedUserId&experimentKey=$encodedExperimentKey")

            val request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .timeout(timeout)
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == HTTP_OK) {
                objectMapper.readValue(response.body())
            } else {
                // HTTP 상태 코드별 로깅 레벨 차별화
                val statusCode = response.statusCode()
                when {
                    statusCode in 400..499 -> {
                        // 4xx: 클라이언트 에러 (잘못된 요청, 권한 없음 등)
                        logger.warn("Client error during variant assignment: HTTP $statusCode, userId=$maskedUserId, experimentKey=$experimentKey")
                    }
                    statusCode in 500..599 -> {
                        // 5xx: 서버 에러 (내부 오류, 서비스 불가 등)
                        logger.error("Server error during variant assignment: HTTP $statusCode, userId=$maskedUserId, experimentKey=$experimentKey")
                    }
                    else -> {
                        // 예상치 못한 상태 코드
                        logger.warn("Unexpected HTTP status during variant assignment: HTTP $statusCode, userId=$maskedUserId, experimentKey=$experimentKey")
                    }
                }
                createErrorResponse(userId, experimentKey, "HTTP $statusCode")
            }
        } catch (e: HttpTimeoutException) {
            // 타임아웃 예외 명시적 처리
            logger.warn("Timeout during variant assignment (timeout=${timeout.toMillis()}ms): userId=$maskedUserId, experimentKey=$experimentKey", e)
            createErrorResponse(userId, experimentKey, "Timeout after ${timeout.toMillis()}ms")
        } catch (e: Exception) {
            logger.error("Error assigning variant for userId=$maskedUserId, experimentKey=$experimentKey", e)
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
        val maskedUserId = maskUserId(userId)

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
                .timeout(timeout)
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != HTTP_OK) {
                // HTTP 상태 코드별 로깅 레벨 차별화
                val statusCode = response.statusCode()
                when {
                    statusCode in 400..499 -> {
                        // 4xx: 클라이언트 에러
                        logger.warn("Client error during conversion tracking: HTTP $statusCode, userId=$maskedUserId, experimentKey=$experimentKey, eventName=$eventName")
                    }
                    statusCode in 500..599 -> {
                        // 5xx: 서버 에러
                        logger.error("Server error during conversion tracking: HTTP $statusCode, userId=$maskedUserId, experimentKey=$experimentKey, eventName=$eventName")
                    }
                    else -> {
                        // 예상치 못한 상태 코드
                        logger.warn("Unexpected HTTP status during conversion tracking: HTTP $statusCode, userId=$maskedUserId, experimentKey=$experimentKey, eventName=$eventName")
                    }
                }
            }
        } catch (e: HttpTimeoutException) {
            // 타임아웃 예외 명시적 처리
            logger.warn("Timeout during conversion tracking (timeout=${timeout.toMillis()}ms): userId=$maskedUserId, experimentKey=$experimentKey, eventName=$eventName", e)
        } catch (e: Exception) {
            logger.error("Error tracking conversion for userId=$maskedUserId, experimentKey=$experimentKey, eventName=$eventName", e)
            // 조용히 실패 (예외 던지지 않음)
        }
    }

    /**
     * 개인정보 보호를 위해 userId를 마스킹합니다.
     *
     * GDPR 및 개인정보 보호 규정 준수를 위해 로그에 userId를 직접 노출하지 않습니다.
     *
     * **마스킹 규칙:**
     * - 4자 초과: 처음 2자 + "***" + 마지막 2자 (예: "user12345" → "us***45")
     * - 4자 이하: 완전 마스킹 "***" (예: "abc" → "***")
     *
     * @param userId 원본 사용자 ID
     * @return 마스킹된 사용자 ID
     */
    private fun maskUserId(userId: String): String {
        return if (userId.length > 4) {
            "${userId.take(2)}***${userId.substring(userId.length - 2)}"
        } else {
            "***"
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
            resultCode = ERROR_CODE,
            resultMessage = "Assignment failed: $errorMessage"
        )
    }
}
