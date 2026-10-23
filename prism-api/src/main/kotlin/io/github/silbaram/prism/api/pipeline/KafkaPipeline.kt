package io.github.silbaram.prism.api.pipeline

import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

@Component @ConditionalOnProperty(name = ["prism.pipeline.mode"], havingValue = "KAFKA")
class KafkaPipeline(private val properties: PipelineProperties, private val storage: PipelineStorage, private val transport: KafkaTransport,
                    private val inbox: PipelineInboxRepository, private val outbox: PipelineOutboxRepository) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean()
    private val consumer = AtomicReference(if (properties.consumerEnabled) KafkaConsumer<String, String>(properties.clientProperties + mapOf(
        "bootstrap.servers" to properties.bootstrapServers, "group.id" to properties.groupId,
        "key.deserializer" to StringDeserializer::class.java.name, "value.deserializer" to StringDeserializer::class.java.name,
        "enable.auto.commit" to "false", "auto.offset.reset" to "earliest", "max.poll.records" to "1",
        "isolation.level" to "read_committed"
    )) else null)
    private val workers = Executors.newScheduledThreadPool(3) { Thread(it, "prism-event-pipeline").apply { isDaemon = true } }
    init {
        if (properties.consumerEnabled) {
            workers.execute { consume() }
            workers.scheduleWithFixedDelay({ materialize() }, 1, 1, TimeUnit.SECONDS)
            workers.scheduleWithFixedDelay({ export() }, 1, 1, TimeUnit.SECONDS)
        }
    }
    private fun consume() {
        val client = requireNotNull(consumer.get())
        var checkAt = 0L
        var backpressured = false
        try {
            client.subscribe(listOf(properties.topic))
            while (!closed.get()) {
                try {
                    if (System.nanoTime() >= checkAt) {
                        // Waiting conversions cannot stop intake: their exposures may be later in Kafka.
                        backpressured = outbox.count() >= properties.maxOutboxRows
                        checkAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                    }
                    if (backpressured) client.pause(client.assignment()) else client.resume(client.assignment())
                    for (record in client.poll(Duration.ofMillis(500))) {
                        if (closed.get()) break
                        val partition = TopicPartition(record.topic(), record.partition())
                        if (backpressured) { client.seek(partition, record.offset()); continue }
                        try {
                            storage.receive(record.value() ?: "null", "${record.topic()}:${record.partition()}:${record.offset()}")
                            // Admission may finish during shutdown. Leave its offset uncommitted;
                            // replay will deduplicate the durable inbox row after restart.
                            if (closed.get()) break
                            client.commitSync(mapOf(partition to OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(5))
                        } catch (error: WakeupException) {
                            throw error
                        } catch (_: RuntimeException) {
                            if (closed.get()) break
                            client.seek(partition, record.offset())
                            TimeUnit.MILLISECONDS.sleep(500)
                        }
                    }
                } catch (_: WakeupException) { if (closed.get()) break }
                catch (_: RuntimeException) { if (!closed.get()) TimeUnit.MILLISECONDS.sleep(500) }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally {
            consumer.set(null)
            // A forced worker cancellation must not enter Kafka's close with an already-set flag.
            val interrupted = Thread.interrupted()
            try { client.close(CloseOptions.timeout(Duration.ofSeconds(5))) }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }
    fun materialize() {
        if (closed.get()) return
        try {
            for (id in inbox.due(LocalDateTime.now(ZoneOffset.UTC), PageRequest.of(0, 100))) {
                if (closed.get()) break
                storage.process(id)
            }
        }
        catch (_: RuntimeException) { /* Durable inbox remains pending; retry next tick. */ }
    }
    fun export() {
        if (closed.get()) return
        try {
            for (id in outbox.pending(PageRequest.of(0, 100))) {
                if (closed.get()) break
                storage.publish(id) {
                    val topic = if (it.kind == "WAREHOUSE") properties.warehouseTopic else properties.deadLetterTopic
                    transport.send(topic, it.id, it.payload).get(5, TimeUnit.SECONDS)
                }
            }
        } catch (_: Exception) { /* An acknowledged-but-uncommitted export can repeat; sinks deduplicate by ID. */ }
    }
    @PreDestroy fun close() {
        if (!closed.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        consumer.get()?.wakeup()
        // Cancel future periodic executions, but let the current DB write/export and consumer close finish.
        workers.shutdown()
        try {
            if (!workers.awaitTermination(6, TimeUnit.SECONDS)) {
                workers.shutdownNow()
                awaitForcedShutdown(deadline)
            }
        } catch (_: InterruptedException) {
            workers.shutdownNow()
            try { awaitForcedShutdown(deadline) }
            finally { Thread.currentThread().interrupt() }
        }
    }

    private fun awaitForcedShutdown(deadline: Long) {
        try {
            if (!workers.awaitTermination((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
                logger.warn("Pipeline workers are still running after the shutdown budget; pending records may be retried")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Pipeline shutdown was interrupted; pending records may be retried")
        }
    }
}
