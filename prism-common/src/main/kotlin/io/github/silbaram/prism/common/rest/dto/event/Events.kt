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
    val exposureEventId: String? = null,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    val analysis: ExposureAnalysisContext? = null
)

data class EventsRequest(val events: List<ClientEvent>)
data class EventsResponse(val results: List<EventResult>)

/** ACCEPTED/DUPLICATE are materialized; QUEUED is durably admitted to the pipeline. */
data class EventResult(val eventId: String, val status: EventStatus, val message: String? = null)
enum class EventStatus { ACCEPTED, DUPLICATE, QUEUED, REJECTED, RETRY }
