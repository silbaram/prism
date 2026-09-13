package io.github.silbaram.prism.api.event

import io.github.silbaram.prism.common.rest.dto.event.*
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
class EventsController(private val ingestion: EventIngestionService) {
    @PostMapping("/v1/events")
    fun events(@RequestBody request: EventsRequest): EventsResponse {
        if (request.events.size !in 1..1000 || request.events.map { it.eventId }.toSet().size != request.events.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Send 1-1000 events with distinct event IDs")
        }
        return ingestion.ingest(request.events)
    }
}
