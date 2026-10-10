package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.dto.assign.SupportedAssignmentRequest
import io.github.silbaram.prism.common.rest.dto.assign.validateSupportedVariants
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionRequest
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionResponse
import io.github.silbaram.prism.common.rest.dto.conversion.SupportedConversionRequest
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration
import io.github.silbaram.prism.common.rest.dto.event.ExposureAnalysisContext

/** Local evaluation by default. REMOTE retains the legacy synchronous HTTP contract. Close on shutdown. */
class PrismClient @JvmOverloads constructor(
    private val baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(5),
    options: PrismClientOptions = PrismClientOptions()
) : AutoCloseable {
    /** Convenient authenticated construction for Java consumers. */
    @JvmOverloads
    constructor(baseUrl: String, apiKey: String, timeout: Duration = Duration.ofSeconds(5)) :
        this(baseUrl, timeout, PrismClientOptions(apiKey = apiKey))

    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)
    private val apiKey = options.apiKey
    // A strategy registry remains fixed for this client. Multiple registries for one experiment
    // must all support its treatments, so restrictions can only become more conservative.
    private val supportedVariants = ConcurrentHashMap<String, Set<String>>()
    private val local = if (options.evaluationMode == EvaluationMode.LOCAL)
        LocalEvaluationClient(baseUrl.trimEnd('/'), timeout, options, client) else null

    /** Local: evaluate and enqueue exposure. Remote: synchronously persist exposure. */
    @JvmOverloads
    fun assign(userId: String, experimentKey: String, attributes: Map<String, Any> = emptyMap(), analysis: ExposureAnalysisContext? = null): AssignmentResponse {
        val available = supportedVariants[experimentKey]
        if (available?.isEmpty() == true) return errorResponse(userId, experimentKey, "No common supported variants")
        return local?.assign(userId, experimentKey, attributes, analysis, available)
            ?: if (attributes.isNotEmpty() || analysis != null) errorResponse(userId, experimentKey, "Attributes require local evaluation")
            else if (available == null) fetchAssignment("assign", userId, experimentKey)
            else fetchSupportedAssignment(userId, experimentKey, available)
    }

    /** Register all available strategy names without reallocating users into the supported subset. */
    fun assignSupported(userId: String, experimentKey: String, variants: Set<String>): AssignmentResponse = try {
        require(userId.isNotBlank() && userId.length <= 255 && experimentKey.isNotBlank() && experimentKey.length <= 255)
        val registered = try {
            variants.toSet().also(::validateSupportedVariants)
        } catch (exception: Exception) {
            // A rejected registry must not leave old exposures eligible for unrestricted
            // conversions, including the first registration of an empty/invalid registry.
            supportedVariants[experimentKey] = emptySet()
            throw exception
        }
        val available = requireNotNull(supportedVariants.compute(experimentKey) { _, previous ->
            previous?.intersect(registered) ?: registered
        })
        if (available.isEmpty()) errorResponse(userId, experimentKey, "No common supported variants")
        else assign(userId, experimentKey)
    } catch (exception: Exception) {
        if (exception is InterruptedException) Thread.currentThread().interrupt()
        errorResponse(userId, experimentKey, exception.javaClass.simpleName)
    }

    private fun fetchSupportedAssignment(userId: String, experimentKey: String, available: Set<String>): AssignmentResponse = try {
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/v1/assign/supported"))
            .timeout(timeout).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                SupportedAssignmentRequest(userId, experimentKey, available)))).build()
        val response = sendWithTimeout(client, request, timeout, apiKey)
        if (response.statusCode() != 200) errorResponse(userId, experimentKey, "HTTP ${response.statusCode()}")
        else objectMapper.readValue<AssignmentResponse>(response.body()).let { result ->
            if (result.userId != userId || result.experimentKey != experimentKey ||
                (result.variant != null && result.variant !in available))
                errorResponse(userId, experimentKey, "Unsupported or mismatched assignment response")
            else result
        }
    } catch (exception: Exception) {
        if (exception is InterruptedException) Thread.currentThread().interrupt()
        errorResponse(userId, experimentKey, exception.javaClass.simpleName)
    }

    /** Pure local evaluation. Call recordExposure only when the experience is actually shown. */
    @JvmOverloads
    fun evaluate(userId: String, experimentKey: String, attributes: Map<String, Any> = emptyMap()): AssignmentResponse =
        local?.evaluate(userId, experimentKey, attributes, supportedVariants[experimentKey])
            ?: errorResponse(userId, experimentKey, "Pure evaluation requires local mode")

    @JvmOverloads
    fun recordExposure(assignment: AssignmentResponse, analysis: ExposureAnalysisContext? = null): Boolean {
        val evaluation = local ?: return false
        supportedVariants[assignment.experimentKey]?.let { available ->
            if (assignment.variant?.let { it in available } != true ||
                !evaluation.supportsVariants(assignment.experimentKey, available)) return false
        }
        return evaluation.recordExposure(assignment, analysis)
    }
    fun refreshConfig(): Boolean = local?.refreshConfig() ?: false
    fun isInHoldout(userId: String): Boolean? = local?.isInHoldout(userId)
    fun recordPopulationExposure(userId: String): Boolean = local?.recordPopulationExposure(userId) ?: false
    fun trackPopulationConversion(userId: String, eventName: String): Boolean = local?.trackPopulationConversion(userId, eventName) ?: false
    fun flush(): Boolean = local?.flush() ?: true
    val pendingEventCount: Int get() = local?.pendingEventCount ?: 0
    /** Rejected targeting definitions by experiment key; excludes rule text and user attributes. */
    val targetingConfigurationErrors: Map<String, String> get() = local?.targetingConfigurationErrors ?: emptyMap()
    /** Lifetime user/experiment dedup entries, retained after acknowledgement; zero in REMOTE mode. */
    val exposureDedupCount: Int get() = local?.exposureDedupCount ?: 0
    /** Separately bounded population-user dedup entries; zero in REMOTE mode. */
    val populationExposureDedupCount: Int get() = local?.populationExposureDedupCount ?: 0
    /** Capacity-rejected registration attempts over this client's lifetime, including repeated attempts. */
    val exposureDedupRejectedCount: Long get() = local?.exposureDedupRejectedCount ?: 0
    val populationExposureDedupRejectedCount: Long get() = local?.populationExposureDedupRejectedCount ?: 0
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
            val response = sendWithTimeout(client, request, timeout, apiKey)
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
    fun trackConversion(assignment: AssignmentResponse, eventName: String): Boolean {
        if (local != null && !supportsLocalConversion(assignment.experimentKey)) return false
        return local?.trackConversion(assignment, eventName, supportedVariants[assignment.experimentKey])
            ?: (assignment.resultCode == ResponseCode.SUCCESS.code && !assignment.variant.isNullOrBlank() &&
                trackConversion(assignment.userId, assignment.experimentKey, eventName))
    }

    /** Tracks against the most recent known exposure, refreshing it after exposureCacheTtl. */
    @JvmOverloads
    fun trackConversion(userId: String, experimentKey: String, eventName: String,
                        exposureCacheTtl: Duration = Duration.ofSeconds(30)): Boolean {
        local?.let {
            if (!supportsLocalConversion(experimentKey)) return false
            return it.trackConversion(userId, experimentKey, eventName, exposureCacheTtl, supportedVariants[experimentKey])
        }
        return try {
            val available = supportedVariants[experimentKey]
            if (available?.isEmpty() == true) return false
            val payload = if (available == null) ConversionRequest(experimentKey = experimentKey, userId = userId, eventName = eventName)
                else SupportedConversionRequest(userId, experimentKey, eventName, available)
            val path = if (available == null) "conversions" else "conversions/supported"
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl.trimEnd('/')}/v1/$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .timeout(timeout).build()
            val response = sendWithTimeout(client, request, timeout, apiKey)
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

    private fun supportsLocalConversion(experimentKey: String): Boolean = supportedVariants[experimentKey]?.let {
        local?.supportsVariants(experimentKey, it) == true
    } ?: true

    private fun errorResponse(userId: String, experimentKey: String, error: String): AssignmentResponse {
        logger.warn("Assignment request failed: userId={}, experimentKey={}, error={}", maskUserId(userId), experimentKey, error)
        return AssignmentResponse(userId, experimentKey, null, SdkResponseCode.CLIENT_ERROR.code, "Assignment failed: $error")
    }
}
