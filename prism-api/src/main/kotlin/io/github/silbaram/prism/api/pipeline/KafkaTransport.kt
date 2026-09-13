package io.github.silbaram.prism.api.pipeline

import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Future

@Component @ConditionalOnProperty(name = ["prism.pipeline.mode"], havingValue = "KAFKA")
class KafkaTransport(private val properties: PipelineProperties) {
    init {
        require(properties.bootstrapServers.isNotBlank()) { "Configure PRISM_PIPELINE_BOOTSTRAP_SERVERS" }
        val topics = listOf(properties.topic, properties.warehouseTopic, properties.deadLetterTopic)
        require(topics.toSet().size == 3 && topics.all { it.matches(Regex("[A-Za-z0-9._-]{1,249}")) && it !in setOf(".", "..") })
        require(properties.groupId.isNotBlank())
        require(properties.maxOutboxRows > 0)
    }
    private val producer = KafkaProducer<String, String>(properties.clientProperties + mapOf(
        "bootstrap.servers" to properties.bootstrapServers, "key.serializer" to StringSerializer::class.java.name,
        "value.serializer" to StringSerializer::class.java.name, "acks" to "all", "enable.idempotence" to "true",
        "delivery.timeout.ms" to "10000", "request.timeout.ms" to "5000", "max.block.ms" to "5000"
    ))
    fun send(topic: String, key: String, payload: String): Future<RecordMetadata> = producer.send(ProducerRecord(topic, key, payload))
    @PreDestroy fun close() = producer.close(Duration.ofSeconds(5))
}
