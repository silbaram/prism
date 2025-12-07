package io.github.silbaram.prism.sdk

import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.dto.conversion.ConversionRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class PrismClient(
    private val baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(5)
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    private val objectMapper = jacksonObjectMapper()

    fun assign(userId: String, experimentKey: String): AssignmentResponse {
        val uri = URI.create("$baseUrl/v1/assign?userId=$userId&experimentKey=$experimentKey")
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RuntimeException("Failed to assign variant: ${response.statusCode()} - ${response.body()}")
        }

        return objectMapper.readValue(response.body())
    }

    fun trackConversion(userId: String, experimentKey: String, eventName: String) {
        val uri = URI.create("$baseUrl/v1/events/conversion")
        val payload = ConversionRequest(
            experimentKey = experimentKey,
            userId = userId,
            eventName = eventName
        )
        val jsonBody = objectMapper.writeValueAsString(payload)

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RuntimeException("Failed to track conversion: ${response.statusCode()} - ${response.body()}")
        }
    }
}
