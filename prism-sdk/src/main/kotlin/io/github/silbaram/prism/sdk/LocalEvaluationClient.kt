package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.dto.config.ConfigResponse
import io.github.silbaram.prism.common.rest.dto.event.*
import io.github.silbaram.prism.core.model.Experiment
import io.github.silbaram.prism.core.model.Variant
import io.github.silbaram.prism.core.model.validateExperimentIdentities
import io.github.silbaram.prism.core.splitter.TrafficSplitter
import io.github.silbaram.prism.core.targeting.TargetingRule
import io.github.silbaram.prism.core.targeting.UserContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.net.http.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.concurrent.locks.ReentrantLock

internal class LocalEvaluationClient(
    private val baseUrl: String,
    private val timeout: Duration,
    private val options: PrismClientOptions,
    private val http: HttpClient
) : AutoCloseable {
    private data class Snapshot(val version: String, val etag: String?, val experiments: Map<String, Experiment>,
                                val holdout: io.github.silbaram.prism.core.model.HoldoutPolicy, val revision: Long, val holdoutConfigured: Boolean)
    private data class Key(val userId: String, val experimentKey: String)
    private data class CachedExposure(val event: ClientEvent, val cachedAt: Long = System.nanoTime())
    private val mapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)
    private val snapshot = AtomicReference<Snapshot?>()
    private val initialized = CountDownLatch(1)
    private val closed = AtomicBoolean()
    private val shutdownCompleted = CountDownLatch(1)
    private val refreshLock = Any()
    private val fetchLock = Any()
    private val queue = LinkedHashMap<String, ClientEvent>()
    private val exposures = Caffeine.newBuilder().maximumSize(options.exposureCacheMaximumSize).build<Key, CachedExposure>()
    // Pending exposures must survive cache expiry/eviction until the collector acknowledges them.
    private val pendingExposures = HashMap<Key, ClientEvent>()
    // Lifetime deduplication is separate from the bounded, expiring server lookup cache.
    // Keep the original reference/version so conversions still point to an actual exposure.
    private val recordedExposures = HashMap<Key, ClientEvent>()
    private val populationExposures = HashMap<String, ClientEvent>()
    private val rejectedExposures = Caffeine.newBuilder().maximumSize(options.eventQueueCapacity.toLong())
        .build<String, Boolean>()
    private val flushLock = ReentrantLock()
    private val flushScheduled = AtomicBoolean()
    private fun scheduler(name: String) = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }
    private val configWorker = scheduler("prism-config-sync")
    private val eventWorker = scheduler("prism-event-flush")
    private val stream = if (options.configStreaming) ConfigStreamClient(baseUrl, timeout, options.apiKey, http) { installConfig(it, null) } else null
    private val shutdownHook = Thread({ close() }, "prism-shutdown")

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        configWorker.scheduleWithFixedDelay({ refreshConfig() }, 0, options.configSyncInterval.toMillis(), TimeUnit.MILLISECONDS)
        eventWorker.scheduleWithFixedDelay({ flush() }, options.eventFlushInterval.toMillis(),
            options.eventFlushInterval.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun refreshConfig(): Boolean = synchronized(fetchLock) {
        if (closed.get()) return false
        try {
            val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/config")).timeout(timeout).GET()
            snapshot.get()?.etag?.let { request.header("If-None-Match", it) }
            val response = send(request.build())
            if (closed.get()) return false
            if (response.statusCode() == 304) return snapshot.get() != null
            check(response.statusCode() == 200) { "Configuration HTTP ${response.statusCode()}" }
            installConfig(response.body(), response.headers().firstValue("ETag").orElse(null))
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Configuration sync failed; retaining last successful snapshot: {}", e.javaClass.simpleName)
            false
        }
    }

    private fun installConfig(body: String, etag: String?): Boolean = synchronized(refreshLock) {
        if (closed.get()) return false
        val config = mapper.readValue<ConfigResponse>(body)
        require(config.revision >= 0)
        if (config.revision < (snapshot.get()?.revision ?: 0)) return false
        require(config.version.matches(Regex("[0-9a-f]{64}"))) { "Invalid configuration version" }
        require(config.experiments.map { it.key }.toSet().size == config.experiments.size) { "Duplicate experiment keys" }
        val holdout = io.github.silbaram.prism.core.model.HoldoutPolicy(config.holdout.key, config.holdout.basisPoints)
        require(config.holdout.configured || config.holdout.basisPoints == 0) { "An unconfigured holdout cannot exclude users" }
        snapshot.get()?.takeIf { it.holdoutConfigured }?.let {
            require(config.holdout.configured && it.holdout == holdout) { "A permanent holdout cannot change" }
        }
        val definitions = config.experiments.associate { definition ->
            require(definition.status == "ACTIVE") { "Invalid active experiment" }
            validateExperimentIdentities(definition.key, definition.variants.map { it.name })
            definition.key to Experiment(definition.key, definition.variants.map { Variant(it.name, it.weight) },
                definition.targetingRules.map { TargetingRule(it) }, definition.trafficAllocation,
                definition.startsAt?.let(Instant::parse), definition.endsAt?.let(Instant::parse),
                definition.layer?.let { io.github.silbaram.prism.core.model.LayerAllocation(it.key, it.start, it.end) },
                holdout, definition.stickyBucketing)
        }
        // Replace only after the entire response validates, including an empty active set.
        definitions.values.filter { it.layer != null }.groupBy { it.layer!!.key }.values.forEach { layer ->
            val ranges = layer.map { it.layer!! }.sortedBy { it.start }
            require(ranges.zipWithNext().all { (left, right) -> left.end <= right.start }) { "Overlapping layer allocations" }
        }
        snapshot.set(Snapshot(config.version, etag, definitions, holdout, config.revision, config.holdout.configured))
        initialized.countDown()
        true
    }

    /** Pure evaluation; does not send or enqueue an exposure. */
    fun evaluate(userId: String, experimentKey: String, attributes: Map<String, Any>): AssignmentResponse {
        if (closed.get() || !validIdentity(userId) || !validIdentity(experimentKey)) return failed(userId, experimentKey)
        if (snapshot.get() == null) {
            try { initialized.await(options.initializationTimeout.toMillis(), TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return failed(userId, experimentKey) }
        }
        if (closed.get()) return failed(userId, experimentKey)
        val state = snapshot.get() ?: return failed(userId, experimentKey)
        val experiment = state.experiments[experimentKey]
        val variant = try { experiment?.let {
            val proposed = TrafficSplitter.assign(it, userId, UserContext(attributes)) ?: return@let null
            if (!it.stickyBucketing) proposed else {
                val stored = options.stickyAssignmentStore.getOrPut(userId, experimentKey, proposed.name)
                it.variants.firstOrNull { candidate -> candidate.name == stored }
            }
        } }
            catch (_: Exception) { return failed(userId, experimentKey) }
        val code = if (variant == null) ResponseCode.EXPERIMENT_NOT_FOUND else ResponseCode.SUCCESS
        return AssignmentResponse(userId, experimentKey, variant?.name, code.code, code.message, state.version)
    }

    fun assign(userId: String, experimentKey: String, attributes: Map<String, Any>, analysis: ExposureAnalysisContext? = null): AssignmentResponse {
        val result = evaluate(userId, experimentKey, attributes)
        if (result.variant == null) return result
        val exposure = enqueueExposure(result, analysis) ?: return failed(userId, experimentKey)
        return result.copy(configVersion = exposure.configVersion, exposureEventId = exposure.eventId)
    }

    fun recordExposure(result: AssignmentResponse, analysis: ExposureAnalysisContext? = null): Boolean = enqueueExposure(result, analysis) != null

    /** null means no validated configuration is available; never guess the comparison cohort. */
    fun isInHoldout(userId: String): Boolean? = if (closed.get() || !validIdentity(userId)) null else snapshot.get()?.takeIf { it.holdoutConfigured }?.holdout?.excludes(userId)

    fun recordPopulationExposure(userId: String): Boolean {
        if (closed.get() || !validIdentity(userId)) return false
        if (snapshot.get() == null) try { initialized.await(options.initializationTimeout.toMillis(), TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        val state = snapshot.get()?.takeIf { it.holdoutConfigured } ?: return false
        synchronized(queue) {
            if (closed.get()) return false
            if (populationExposures.containsKey(userId)) return true
            if (populationExposures.size >= options.exposureDedupCapacity) return false
            val event = ClientEvent(UUID.randomUUID().toString(), "population_exposure", userId, state.holdout.key,
                if (state.holdout.excludes(userId)) "HOLDOUT" else "ELIGIBLE", Instant.now().toString(), state.version)
            if (!enqueue(event)) return false
            populationExposures[userId] = event
        }
        scheduleFlush()
        return true
    }

    fun trackPopulationConversion(userId: String, eventName: String): Boolean {
        if (!validIdentity(eventName)) return false
        val queued = synchronized(queue) {
            val exposure = populationExposures[userId] ?: return false
            enqueueConversion(exposure, eventName, "population_conversion")
        }
        if (queued) scheduleFlush()
        return queued
    }

    private fun enqueueExposure(result: AssignmentResponse, analysis: ExposureAnalysisContext? = null): ClientEvent? {
        try { analysis?.validate() } catch (_: IllegalArgumentException) { return null }
        val variant = result.variant ?: return null
        val version = result.configVersion ?: return null
        if (result.resultCode != ResponseCode.SUCCESS.code || !version.matches(Regex("[0-9a-f]{64}")) ||
            !validIdentity(result.userId) || !validIdentity(result.experimentKey) || !validIdentity(variant)) return null
        val event = ClientEvent(UUID.randomUUID().toString(), "exposure", result.userId, result.experimentKey,
            variant, Instant.now().toString(), version, analysis = analysis?.copy(segments = analysis.segments.toMap()))
        synchronized(queue) {
            if (closed.get()) return null
            val key = Key(result.userId, result.experimentKey)
            recordedExposures[key]?.takeIf { it.variant == variant }?.let {
                exposures.put(key, CachedExposure(it))
                return it
            }
            if (key !in recordedExposures && recordedExposures.size >= options.exposureDedupCapacity) {
                logger.warn("Exposure deduplication capacity reached; rejecting new user/experiment exposure")
                return null
            }
            // A changed variant must remain observable as contamination; never attribute it to the old variant.
            if (!enqueue(event)) return null
            recordedExposures[key] = event
            exposures.put(key, CachedExposure(event))
            pendingExposures[key] = event
        }
        scheduleFlush()
        return event
    }

    fun getAssignment(userId: String, experimentKey: String, cacheTtl: Duration): AssignmentResponse {
        val exposure = withExposure(userId, experimentKey, cacheTtl) { it }
        return if (exposure == null) AssignmentResponse(userId, experimentKey, null,
            ResponseCode.IMPRESSION_NOT_FOUND.code, ResponseCode.IMPRESSION_NOT_FOUND.message)
        else AssignmentResponse(userId, experimentKey, exposure.variant, ResponseCode.SUCCESS.code,
            ResponseCode.SUCCESS.message, exposure.configVersion, exposure.eventId)
    }

    /** Resolve and consume under one monitor; cache admission/eviction must not change the result. */
    private fun <T> withExposure(userId: String, experimentKey: String, cacheTtl: Duration, consume: (ClientEvent) -> T): T? {
        require(!cacheTtl.isNegative) { "Exposure cache TTL must not be negative" }
        if (closed.get()) return null
        val key = Key(userId, experimentKey)
        val before = synchronized(queue) {
            pendingExposures[key]?.let { return consume(it) }
            exposures.getIfPresent(key)?.also {
                if (Duration.ofNanos(System.nanoTime() - it.cachedAt) < cacheTtl) return consume(it.event)
            }
        }
        // HTTP runs outside both the queue monitor and the cache's per-key lock.
        val lookedUp = lookupExposure(userId, experimentKey)
        return synchronized(queue) {
            if (closed.get()) return null
            pendingExposures[key]?.let { return consume(it) }
            val current = exposures.getIfPresent(key)
            // An assignment/lookup completed while HTTP was in flight: keep its newer reference.
            val resolved = if (current != null && current !== before) current.event
                else lookedUp?.also { exposures.put(key, CachedExposure(it)) }
            resolved?.let(consume)
        }
    }

    private fun lookupExposure(userId: String, experimentKey: String): ClientEvent? {
        if (!validIdentity(userId) || !validIdentity(experimentKey) || closed.get()) return null
        return try {
            val user = URLEncoder.encode(userId, StandardCharsets.UTF_8)
            val key = URLEncoder.encode(experimentKey, StandardCharsets.UTF_8)
            val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/assignments?userId=$user&experimentKey=$key&order=OCCURRED_AT"))
                .timeout(timeout).GET().build()
            val response = send(request)
            if (response.statusCode() != 200 || closed.get()) return null
            val result = mapper.readValue<AssignmentResponse>(response.body())
            val id = result.exposureEventId ?: return null
            val version = result.configVersion ?: return null
            val variant = result.variant ?: return null
            if (result.userId != userId || result.experimentKey != experimentKey ||
                result.resultCode != ResponseCode.SUCCESS.code || UUID.fromString(id).toString() != id ||
                !version.matches(Regex("[0-9a-f]{64}")) || !validIdentity(variant)) return null
            ClientEvent(id, "exposure", userId, experimentKey, variant, Instant.now().toString(), version)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Exposure lookup failed: {}", e.javaClass.simpleName)
            null
        }
    }

    /** true means queued locally. Server acknowledgement is reported by flush(). */
    fun trackConversion(userId: String, experimentKey: String, eventName: String, cacheTtl: Duration): Boolean {
        if (!validIdentity(eventName)) return false
        val queued = withExposure(userId, experimentKey, cacheTtl) { exposure ->
            enqueueConversion(exposure, eventName)
        } ?: false
        if (queued) scheduleFlush()
        return queued
    }

    /** An explicit reference can cross process boundaries even before the exposure batch arrives. */
    fun trackConversion(assignment: AssignmentResponse, eventName: String): Boolean {
        val id = assignment.exposureEventId ?: return false
        val version = assignment.configVersion ?: return false
        val variant = assignment.variant ?: return false
        if (assignment.resultCode != ResponseCode.SUCCESS.code || !validIdentity(eventName) ||
            !listOf(assignment.userId, assignment.experimentKey, variant).all(::validIdentity) ||
            !version.matches(Regex("[0-9a-f]{64}")) || !canonicalUuid(id)) return false
        val exposure = ClientEvent(id, "exposure", assignment.userId, assignment.experimentKey,
            variant, Instant.now().toString(), version)
        val queued = synchronized(queue) { enqueueConversion(exposure, eventName) }
        if (queued) scheduleFlush()
        return queued
    }

    private fun enqueueConversion(exposure: ClientEvent, eventName: String, type: String = "conversion"): Boolean {
        if (rejectedExposures.getIfPresent(exposure.eventId) == true) return false
        return enqueue(ClientEvent(UUID.randomUUID().toString(), type, exposure.userId, exposure.experimentKey,
            exposure.variant, Instant.now().toString(), exposure.configVersion, eventName, exposure.eventId))
    }

    private fun canonicalUuid(id: String) = try { UUID.fromString(id).toString() == id } catch (_: IllegalArgumentException) { false }

    // Local exposure registration and event enqueueing use the same monitor.
    private fun enqueue(event: ClientEvent): Boolean {
        if (closed.get() || queue.size >= options.eventQueueCapacity) return false
        queue[event.eventId] = event
        return true
    }

    val pendingEventCount: Int get() = synchronized(queue) { queue.size }

    private fun scheduleFlush() {
        if (pendingEventCount < options.eventBatchSize || !flushScheduled.compareAndSet(false, true)) return
        try { eventWorker.execute { try { flush() } finally { flushScheduled.set(false) } } }
        catch (_: RejectedExecutionException) { flushScheduled.set(false) }
    }

    /** Drains at most the events present at entry, within a total deadline. Failed batches retain IDs. */
    fun flush(): Boolean {
        val deadline = System.nanoTime() + options.shutdownTimeout.toNanos()
        try {
            if (!flushLock.tryLock(options.shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) return false
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        try {
            val remaining = synchronized(queue) { queue.keys.toList() }
            var rejected = false
            for (ids in remaining.chunked(options.eventBatchSize)) {
                val budget = deadline - System.nanoTime()
                if (budget <= 0) return false
                val batch = synchronized(queue) { ids.mapNotNull(queue::get) }
                if (batch.isEmpty()) continue
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/events"))
                    .timeout(Duration.ofNanos(minOf(timeout.toNanos(), budget)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(EventsRequest(batch)))).build()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return false
                val response = send(request, Duration.ofNanos(minOf(timeout.toNanos(), remainingNanos)))
                check(response.statusCode() == 200) { "Events HTTP ${response.statusCode()}" }
                val results = mapper.readValue<EventsResponse>(response.body()).results
                check(results.size == batch.size && results.map { it.eventId }.toSet() == batch.map { it.eventId }.toSet()) {
                    "Incomplete or mismatched event acknowledgements"
                }
                synchronized(queue) {
                    results.forEach { result ->
                        if (result.status != EventStatus.RETRY) {
                            val removed = queue.remove(result.eventId)
                            if (removed?.type == "exposure" && result.status != EventStatus.QUEUED) {
                                pendingExposures.remove(Key(removed.userId, removed.experimentKey), removed)
                            }
                            if (result.status == EventStatus.REJECTED) {
                                rejected = true
                                logger.warn("Event rejected: id={}, reason={}", result.eventId, result.message)
                                if (removed?.type == "population_exposure") {
                                    rejectedExposures.put(removed.eventId, true)
                                    populationExposures.remove(removed.userId, removed)
                                    queue.values.removeIf { it.exposureEventId == removed.eventId }
                                }
                                if (removed?.type == "exposure") {
                                    rejectedExposures.put(removed.eventId, true)
                                    queue.values.removeIf { it.exposureEventId == removed.eventId }
                                    val key = Key(removed.userId, removed.experimentKey)
                                    recordedExposures.remove(key, removed)
                                    exposures.getIfPresent(key)?.takeIf { it.event.eventId == removed.eventId }
                                        ?.let { exposures.asMap().remove(key, it) }
                                }
                            }
                        }
                    }
                }
                // RETRY applies to these IDs only; later independent batches can still commit.
            }
            return !rejected && pendingEventCount == 0
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Event delivery failed; retaining queued events: {}", e.javaClass.simpleName)
            return false
        } finally { flushLock.unlock() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            // A JVM hook must wait if an application thread already owns the final delivery.
            try { shutdownCompleted.await(options.shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            return
        }
        try {
            initialized.countDown()
            stream?.close()
            configWorker.shutdownNow()
            eventWorker.shutdown() // Allow an in-flight delivery to complete within flush's total deadline.
            if (!flush()) logger.warn("Client closed with {} undelivered events", pendingEventCount)
        } finally {
            try {
                eventWorker.shutdownNow()
                http.shutdownNow()
                synchronized(queue) { recordedExposures.clear(); populationExposures.clear() }
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) { /* JVM shutdown */ }
            } finally { shutdownCompleted.countDown() }
        }
    }

    /** Bound the entire response body, including a peer that sends headers but never finishes its body. */
    private fun send(request: HttpRequest, budget: Duration = timeout) = sendWithTimeout(http, request, budget, options.apiKey)

    private fun validIdentity(value: String) = value.isNotBlank() && value.length <= 255
    private fun failed(userId: String, experimentKey: String) = AssignmentResponse(userId, experimentKey, null,
        SdkResponseCode.CLIENT_ERROR.code, "Local evaluation unavailable or exposure capacity reached")
}
