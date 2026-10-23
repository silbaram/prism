package io.github.silbaram.prism.admin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.core.JsonParser
import jakarta.annotation.PreDestroy
import io.github.silbaram.prism.common.rest.dto.diagnostics.PipelineDiagnostics
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

enum class PipelineObservationState { NOT_CONFIGURED, AVAILABLE, UNAVAILABLE }
data class PipelineObservation(val state: PipelineObservationState, val snapshot: PipelineDiagnostics?, val lastSuccessAt: Instant?)

@Service
class PipelineMonitor(
    @param:Value("\${prism.monitoring.api-url:}") private val baseUrl: String,
    @param:Value("\${prism.monitoring.api-key:}") private val apiKey: String
) {
    private val mapper = jacksonObjectMapper().registerModule(SimpleModule().addDeserializer(Instant::class.java,
        object : JsonDeserializer<Instant>() {
            override fun deserialize(parser: JsonParser, context: DeserializationContext): Instant = Instant.parse(parser.valueAsString)
        }))
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val lastSuccess = AtomicReference<Instant>()

    fun observe(): PipelineObservation {
        if (baseUrl.isBlank() || apiKey.isBlank()) return PipelineObservation(PipelineObservationState.NOT_CONFIGURED, null, lastSuccess.get())
        try {
            val base = URI.create(baseUrl.trimEnd('/'))
            require(base.scheme in setOf("http", "https") && base.host != null && base.userInfo == null && base.query == null && base.fragment == null)
            val request = HttpRequest.newBuilder(URI.create("${base.toASCIIString()}/api/internal/pipeline/diagnostics"))
                .timeout(Duration.ofSeconds(3)).header("X-Prism-Api-Key", apiKey).GET().build()
            val pending = client.sendAsync(request) { LimitedBody() }
            val response = try { pending.get(3, TimeUnit.SECONDS) } finally { if (!pending.isDone) pending.cancel(true) }
            val body = response.body()
            require(response.statusCode() == 200 && body.size <= 65_536)
            val snapshot = mapper.readValue<PipelineDiagnostics>(body)
            require(snapshot.mode in setOf("DIRECT", "KAFKA"))
            lastSuccess.set(Instant.now())
            return PipelineObservation(PipelineObservationState.AVAILABLE, snapshot, lastSuccess.get())
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: Exception) { /* No credentials, URLs, or response payloads in errors. */ }
        return PipelineObservation(PipelineObservationState.UNAVAILABLE, null, lastSuccess.get())
    }

    @PreDestroy fun close() { client.shutdownNow() }

    private class LimitedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(Long.MAX_VALUE) }
        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                if (item.remaining() > 65_536 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(IllegalArgumentException("Invalid diagnostics response")); return
                }
                val chunk = ByteArray(item.remaining()); item.get(chunk); bytes.write(chunk)
            }
        }
        override fun onError(error: Throwable) { result.completeExceptionally(error) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }
}
