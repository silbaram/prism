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
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Local evaluation by default. REMOTE retains the legacy synchronous HTTP contract. Close on shutdown. */
class PrismClient @JvmOverloads constructor(
    private val baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(5),
    options: PrismClientOptions = PrismClientOptions()
) : AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)
    private val local = if (options.evaluationMode == EvaluationMode.LOCAL)
        LocalEvaluationClient(baseUrl.trimEnd('/'), timeout, options, client) else null

    /** Local: evaluate and enqueue exposure. Remote: synchronously persist exposure. */
    @JvmOverloads
    fun assign(userId: String, experimentKey: String, attributes: Map<String, Any> = emptyMap()): AssignmentResponse =
        local?.assign(userId, experimentKey, attributes)
            ?: if (attributes.isEmpty()) fetchAssignment("assign", userId, experimentKey)
            else errorResponse(userId, experimentKey, "Attributes require local evaluation")

    /** Pure local evaluation. Call recordExposure only when the experience is actually shown. */
    @JvmOverloads
    fun evaluate(userId: String, experimentKey: String, attributes: Map<String, Any> = emptyMap()): AssignmentResponse =
        local?.evaluate(userId, experimentKey, attributes)
            ?: errorResponse(userId, experimentKey, "Pure evaluation requires local mode")

    fun recordExposure(assignment: AssignmentResponse): Boolean = local?.recordExposure(assignment) ?: false
    fun refreshConfig(): Boolean = local?.refreshConfig() ?: false
    fun flush(): Boolean = local?.flush() ?: true
    val pendingEventCount: Int get() = local?.pendingEventCount ?: 0
    override fun close() {
        // In LOCAL mode the winning close/shutdown hook owns transport teardown after its final flush.
        if (local != null) local.close() else client.shutdownNow()
    }

    /** Looks up a prior exposure without assigning or recording a new exposure. */
    @JvmOverloads
    fun getAssignment(userId: String, experimentKey: String, exposureCacheTtl: Duration = Duration.ofSeconds(30)): AssignmentResponse =
        local?.getAssignment(userId, experimentKey, exposureCacheTtl) ?: fetchAssignment("assignments", userId, experimentKey)

    private fun fetchAssignment(path: String, userId: String, experimentKey: String): AssignmentResponse {
        return try {
            val encodedUserId = URLEncoder.encode(userId, StandardCharsets.UTF_8)
            val encodedKey = URLEncoder.encode(experimentKey, StandardCharsets.UTF_8)
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/v1/$path?userId=$encodedUserId&experimentKey=$encodedKey"))
                .GET().timeout(timeout).build()
            val response = sendWithTimeout(client, request, timeout)
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

    /** Local: true means queued against a prior local exposure. Remote: true means server accepted. */
    fun trackConversion(assignment: AssignmentResponse, eventName: String): Boolean =
        local?.trackConversion(assignment, eventName)
            ?: (assignment.resultCode == ResponseCode.SUCCESS.code && !assignment.variant.isNullOrBlank() &&
                trackConversion(assignment.userId, assignment.experimentKey, eventName))

    /** Tracks against the most recent known exposure, refreshing it after exposureCacheTtl. */
    @JvmOverloads
    fun trackConversion(userId: String, experimentKey: String, eventName: String,
                        exposureCacheTtl: Duration = Duration.ofSeconds(30)): Boolean {
        local?.let { return it.trackConversion(userId, experimentKey, eventName, exposureCacheTtl) }
        return try {
            val payload = ConversionRequest(experimentKey = experimentKey, userId = userId, eventName = eventName)
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/v1/conversions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .timeout(timeout).build()
            val response = sendWithTimeout(client, request, timeout)
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
