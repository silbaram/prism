package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionRequest
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionResponse
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Synchronous, fail-safe transport. Conversion results reflect the server's acceptance. */
class PrismClient(
    private val baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(5)
) {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Assigns a variant and records an exposure before returning. */
    fun assign(userId: String, experimentKey: String): AssignmentResponse =
        fetchAssignment("assign", userId, experimentKey)

    /** Looks up a prior exposure without assigning or recording a new exposure. */
    fun getAssignment(userId: String, experimentKey: String): AssignmentResponse =
        fetchAssignment("assignments", userId, experimentKey)

    private fun fetchAssignment(path: String, userId: String, experimentKey: String): AssignmentResponse {
        return try {
            val encodedUserId = URLEncoder.encode(userId, StandardCharsets.UTF_8)
            val encodedKey = URLEncoder.encode(experimentKey, StandardCharsets.UTF_8)
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/v1/$path?userId=$encodedUserId&experimentKey=$encodedKey"))
                .GET().timeout(timeout).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                objectMapper.readValue<AssignmentResponse>(response.body())
            } else {
                errorResponse(userId, experimentKey, "HTTP ${response.statusCode()}")
            }
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            errorResponse(userId, experimentKey, exception.javaClass.simpleName)
        }
    }

    /** Returns true only when the server accepts an event with a prior exposure. Never retries. */
    fun trackConversion(userId: String, experimentKey: String, eventName: String): Boolean {
        return try {
            val payload = ConversionRequest(experimentKey = experimentKey, userId = userId, eventName = eventName)
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/v1/conversions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .timeout(timeout).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val accepted = response.statusCode() == 200 &&
                objectMapper.readValue<ConversionResponse>(response.body()).resultCode == ResponseCode.SUCCESS.code
            if (!accepted) logger.warn("Conversion rejected: userId={}, experimentKey={}, eventName={}, HTTP={}",
                maskUserId(userId), experimentKey, eventName, response.statusCode())
            accepted
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Conversion failed: userId={}, experimentKey={}, error={}",
                maskUserId(userId), experimentKey, exception.javaClass.simpleName)
            false
        }
    }

    private fun errorResponse(userId: String, experimentKey: String, error: String): AssignmentResponse {
        logger.warn("Assignment request failed: userId={}, experimentKey={}, error={}", maskUserId(userId), experimentKey, error)
        return AssignmentResponse(userId, experimentKey, null, SdkResponseCode.CLIENT_ERROR.code, "Assignment failed: $error")
    }
}
