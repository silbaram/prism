package io.github.silbaram.prism.common.rest.dto.event

/** IDs are canonical UUID strings; timestamps are ISO-8601 instants in UTC. */
data class ClientEvent(
    val eventId: String,
    val type: String,
    val userId: String,
    val experimentKey: String,
    val variant: String,
    val timestamp: String,
    val configVersion: String,
    val eventName: String? = null,
    val exposureEventId: String? = null
)

data class EventsRequest(val events: List<ClientEvent>)
data class EventsResponse(val results: List<EventResult>)

/** ACCEPTED/DUPLICATE are committed; REJECTED is permanent; RETRY preserves the original ID. */
data class EventResult(val eventId: String, val status: EventStatus, val message: String? = null)
enum class EventStatus { ACCEPTED, DUPLICATE, REJECTED, RETRY }
