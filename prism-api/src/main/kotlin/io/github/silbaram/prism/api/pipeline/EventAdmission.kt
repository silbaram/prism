package io.github.silbaram.prism.api.pipeline

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.api.event.EventIngestionService
import io.github.silbaram.prism.common.rest.dto.event.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class EventAdmission(private val ingestion: EventIngestionService, private val properties: PipelineProperties,
                     private val transport: ObjectProvider<KafkaTransport>) {
    private val mapper = jacksonObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    fun admit(events: List<ClientEvent>): EventsResponse {
        if (properties.mode == PipelineMode.DIRECT) return ingestion.ingest(events)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val results = events.map { event ->
            try { ingestion.validate(event) } catch (error: IllegalArgumentException) {
                return@map EventResult(event.eventId, EventStatus.REJECTED, error.message)
            }
            if (System.nanoTime() >= deadline) return@map EventResult(event.eventId, EventStatus.RETRY, "Pipeline temporarily unavailable")
            try {
                val future = transport.getObject().send(properties.topic, "${event.experimentKey.length}:${event.experimentKey}:${event.userId}",
                    mapper.writeValueAsString(event))
                future.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                EventResult(event.eventId, EventStatus.QUEUED)
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                EventResult(event.eventId, EventStatus.RETRY, "Pipeline temporarily unavailable")
            }
        }
        return EventsResponse(results)
    }
}
