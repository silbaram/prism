package io.github.silbaram.prism.sdk

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PrismClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: PrismClient

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = PrismClient(mockWebServer.url("/").toString().removeSuffix("/"))
    }

    @AfterEach
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `assign should return assignment response`() {
        val jsonResponse = """
            {
                "userId": "user123",
                "experimentKey": "exp-1",
                "variant": "A",
                "resultCode": "0000",
                "resultMessage": "Success"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val response = client.assign("user123", "exp-1")

        assertEquals("user123", response.userId)
        assertEquals("exp-1", response.experimentKey)
        assertEquals("A", response.variant)
        assertEquals("0000", response.resultCode)
        assertEquals("Success", response.resultMessage)

        val request = mockWebServer.takeRequest()
        assertEquals("/v1/assign?userId=user123&experimentKey=exp-1", request.path)
        assertEquals("GET", request.method)
    }

    @Test
    fun `assign should return error response when API returns error code`() {
        val jsonResponse = """
            {
                "userId": "user123",
                "experimentKey": "invalid-exp",
                "variant": null,
                "resultCode": "9999",
                "resultMessage": "Experiment not found or not active"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setBody(jsonResponse).setResponseCode(200))

        val response = client.assign("user123", "invalid-exp")

        assertEquals("user123", response.userId)
        assertEquals("invalid-exp", response.experimentKey)
        assertEquals(null, response.variant)
        assertEquals("9999", response.resultCode)
        assertEquals("Experiment not found or not active", response.resultMessage)
    }

    @Test
    fun `assign should return error response when HTTP fails`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val response = client.assign("user123", "exp-1")

        assertEquals("user123", response.userId)
        assertEquals("exp-1", response.experimentKey)
        assertEquals(null, response.variant)
        assertEquals("9999", response.resultCode)
        assert(response.resultMessage.contains("HTTP 500"))
    }

    @Test
    fun `assign should return error response when network fails`() {
        mockWebServer.shutdown()

        val response = client.assign("user123", "exp-1")

        assertEquals("user123", response.userId)
        assertEquals("exp-1", response.experimentKey)
        assertEquals(null, response.variant)
        assertEquals("9999", response.resultCode)
        assert(response.resultMessage.contains("Assignment failed"))
    }

    @Test
    fun `trackConversion should send correct payload`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        client.trackConversion("user123", "exp-1", "purchase")

        val request = mockWebServer.takeRequest()
        assertEquals("/v1/conversions", request.path)
        assertEquals("POST", request.method)
        assertEquals("application/json", request.getHeader("Content-Type"))

        // Simple check for body content
        val body = request.body.readUtf8()
        assert(body.contains("user123"))
        assert(body.contains("exp-1"))
        assert(body.contains("purchase"))
    }

    @Test
    fun `trackConversion should not throw exception when HTTP fails`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        // 예외가 발생하지 않아야 함
        client.trackConversion("user123", "exp-1", "purchase")

        val request = mockWebServer.takeRequest()
        assertEquals("/v1/conversions", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun `trackConversion should not throw exception when network fails`() {
        mockWebServer.shutdown()

        // 예외가 발생하지 않아야 함
        client.trackConversion("user123", "exp-1", "purchase")
    }
}
