package io.github.silbaram.prism.api.config

import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentChangeRepository
import jakarta.annotation.PreDestroy
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@Service
class ConfigStreams(private val configs: ConfigService, private val changes: ExperimentChangeRepository) {
    private class Subscriber(val emitter: SseEmitter) {
        val sending = AtomicBoolean()
        @Volatile var version: String? = null
        @Volatile var lastSent: Long = 0
    }
    private val clients = ConcurrentHashMap<SseEmitter, Subscriber>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "prism-config-notifier").apply { isDaemon = true } }
    private val writers = Executors.newVirtualThreadPerTaskExecutor()
    private var revision = -1L
    init { scheduler.scheduleWithFixedDelay({ tick() }, 500, 500, TimeUnit.MILLISECONDS) }

    @Synchronized fun subscribe(): SseEmitter {
        if (clients.size >= 512) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configuration stream capacity reached")
        val emitter = SseEmitter(60_000)
        clients[emitter] = Subscriber(emitter)
        emitter.onCompletion { clients.remove(emitter) }
        emitter.onTimeout { clients.remove(emitter); emitter.complete() }
        emitter.onError { clients.remove(emitter) }
        return emitter
    }

    private fun tick() {
        if (clients.isEmpty()) return
        try {
            val latest = changes.latestRevision()
            if (latest != revision) { configs.invalidate(); revision = latest }
            val config = configs.snapshot()
            clients.values.forEach { subscriber ->
                if (subscriber.version != config.version || System.nanoTime() - subscriber.lastSent > TimeUnit.SECONDS.toNanos(15)) {
                    if (subscriber.sending.compareAndSet(false, true)) writers.submit {
                        try {
                            if (subscriber.version != config.version) {
                                subscriber.emitter.send(SseEmitter.event().name("config").id(config.revision.toString())
                                    .data(config, MediaType.APPLICATION_JSON))
                                subscriber.version = config.version
                            } else subscriber.emitter.send(SseEmitter.event().comment("heartbeat"))
                            subscriber.lastSent = System.nanoTime()
                        } catch (_: Exception) { clients.remove(subscriber.emitter) }
                        finally { subscriber.sending.set(false) }
                    }
                }
            }
        } catch (_: RuntimeException) { /* Keep the current SDK snapshot while the DB is unavailable. */ }
    }

    @PreDestroy fun close() {
        scheduler.shutdownNow()
        clients.keys.forEach { it.complete() }
        clients.clear()
        writers.shutdownNow()
    }
}

@RestController
class ConfigStreamController(private val streams: ConfigStreams) {
    @GetMapping("/v1/config/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): ResponseEntity<SseEmitter> = ResponseEntity.ok().header("Cache-Control", "no-store")
        .header("X-Accel-Buffering", "no").body(streams.subscribe())
}
