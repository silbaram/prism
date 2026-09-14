package io.github.silbaram.prism.common.rest.dto.event

import com.fasterxml.jackson.annotation.*

/** IDs are canonical UUID strings; timestamps are ISO-8601 instants in UTC. */
data class ClientEvent @JvmOverloads @JsonCreator(mode = JsonCreator.Mode.DISABLED) constructor(
    val eventId: String,
    val type: String,
    val userId: String,
    val experimentKey: String,
    val variant: String,
    val timestamp: String,
    val configVersion: String,
    val eventName: String? = null,
    val exposureEventId: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val analysis: ExposureAnalysisContext? = null,
    // A non-bean getter prevents Jackson 2 from treating this map as a regular setterless property.
    @field:JsonAnySetter
    @get:JvmName("extensionFields")
    @get:JsonAnyGetter
    @get:JsonPropertyOrder(alphabetic = true)
    val extensions: Map<String, Any?> = linkedMapOf()
) {
    companion object {
        // Keep the extension collector out of creator properties: a JSON field named
        // "extensions" must itself be preserved, not consumed as a constructor argument.
        @JvmStatic @JsonCreator
        fun fromJson(
            @JsonProperty("eventId") eventId: String,
            @JsonProperty("type") type: String,
            @JsonProperty("userId") userId: String,
            @JsonProperty("experimentKey") experimentKey: String,
            @JsonProperty("variant") variant: String,
            @JsonProperty("timestamp") timestamp: String,
            @JsonProperty("configVersion") configVersion: String,
            @JsonProperty("eventName") eventName: String?,
            @JsonProperty("exposureEventId") exposureEventId: String?,
            @JsonProperty("analysis") analysis: ExposureAnalysisContext?
        ) = ClientEvent(eventId, type, userId, experimentKey, variant, timestamp, configVersion, eventName, exposureEventId, analysis)
    }
}

data class EventsRequest(val events: List<ClientEvent>)
data class EventsResponse(val results: List<EventResult>)

/** ACCEPTED/DUPLICATE are materialized; QUEUED is durably admitted to the pipeline. */
data class EventResult(val eventId: String, val status: EventStatus, val message: String? = null)
enum class EventStatus { ACCEPTED, DUPLICATE, QUEUED, REJECTED, RETRY }
