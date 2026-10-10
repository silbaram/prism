package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.ResponseCode
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrismClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: PrismClient

    @BeforeEach
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = PrismClient(mockWebServer.url("/").toString().removeSuffix("/"), options = PrismClientOptions(evaluationMode = EvaluationMode.REMOTE))
    }

    @AfterEach
    fun teardown() {
        client.close()
        mockWebServer.shutdown()
    }

    @Test
    fun `ordinary remote assignment retains the registered strategy check`() {
        for (user in listOf("registry-user", "next-user")) {
            mockWebServer.enqueue(MockResponse().setBody("""{"userId":"$user","experimentKey":"checkout","variant":"A","resultCode":"0000","resultMessage":"Success"}"""))
        }
        assertEquals("A", client.assignSupported("registry-user", "checkout", setOf("A")).variant)
        assertEquals("A", client.assign("next-user", "checkout").variant)
        repeat(2) {
            val request = mockWebServer.takeRequest()
            assertEquals("/v1/assign/supported", request.path)
            assertEquals(listOf("A"), jacksonObjectMapper().readTree(request.body.readUtf8())["supportedVariants"].map { it.asText() })
        }
    }

    @Test
    fun `empty remote registry blocks old exposures and normal assignment without HTTP writes`() {
        mockWebServer.enqueue(MockResponse().setBody("""{"userId":"u","experimentKey":"checkout","variant":"A","resultCode":"0000","resultMessage":"Success"}"""))
        val previous = client.assign("u", "checkout")
        assertEquals("A", previous.variant)
        assertEquals(null, client.assignSupported("new-user", "checkout", emptySet()).variant)
        mockWebServer.enqueue(MockResponse().setBody("""{"userId":"u","experimentKey":"checkout","eventName":"purchase","variant":"A","resultCode":"0000","resultMessage":"Success"}"""))
        assertFalse(client.trackConversion(previous, "purchase"))
        assertEquals(null, client.assign("new-user", "checkout").variant)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `supported routes preserve strategy identity and never fall back to unsafe legacy routes`() {
        client.close()
        client = PrismClient(mockWebServer.url("/").toString(), options = PrismClientOptions(
            evaluationMode = EvaluationMode.REMOTE, apiKey = "prism-test-api-key-0123456789abcdef"))
        val user = "User +&가"
        val key = "Checkout/&"
        val names = setOf("Control", "할인")
        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        assertEquals(null, client.assignSupported(user, key, names).variant)
        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        assertFalse(client.trackConversion(user, key, "purchase"))
        val mapper = jacksonObjectMapper()
        val assign = mockWebServer.takeRequest()
        val conversion = mockWebServer.takeRequest()
        assertEquals("/v1/assign/supported", assign.path)
        assertEquals("/v1/conversions/supported", conversion.path)
        for (request in listOf(assign, conversion)) {
            assertEquals("POST", request.method)
            assertEquals("prism-test-api-key-0123456789abcdef", request.getHeader("X-Prism-Api-Key"))
            val body = mapper.readTree(request.body.readUtf8())
            assertEquals(user, body["userId"].asText())
            assertEquals(key, body["experimentKey"].asText())
            assertEquals(names, body["supportedVariants"].map { it.asText() }.toSet())
        }
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `supported assignment refuses mismatched identity or unsupported treatment responses`() {
        val mapper = jacksonObjectMapper()
        for (response in listOf(
            AssignmentResponse("other", "checkout", "A", "0000", "Success"),
            AssignmentResponse("u", "other", "A", "0000", "Success"),
            AssignmentResponse("u", "checkout", "B", "0000", "Success"))) {
            mockWebServer.enqueue(MockResponse().setBody(mapper.writeValueAsString(response)))
            assertEquals(SdkResponseCode.CLIENT_ERROR.code, client.assignSupported("u", "checkout", setOf("A")).resultCode)
        }
    }

    @Test
    fun `registry restrictions are copied and cannot be widened by another strategy interface`() {
        val names = mutableSetOf("A", "B")
        repeat(2) { mockWebServer.enqueue(MockResponse().setResponseCode(404)) }
        client.assignSupported("u", "checkout", names)
        names.clear()
        names.add("C")
        client.assignSupported("u", "checkout", setOf("B", "C"))
        mockWebServer.takeRequest()
        val second = jacksonObjectMapper().readTree(mockWebServer.takeRequest().body.readUtf8())
        assertEquals(listOf("B"), second["supportedVariants"].map { it.asText() })
        assertEquals(null, client.assignSupported("u", "checkout", setOf("A")).variant)
        assertFalse(client.trackConversion("u", "checkout", "purchase"))
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `remote timeout bounds delayed response bodies for assignments lookups and conversions`() {
        client.close()
        client = PrismClient(mockWebServer.url("/").toString(), Duration.ofMillis(100),
            PrismClientOptions(evaluationMode = EvaluationMode.REMOTE))
        val worker = Executors.newSingleThreadExecutor()
        try {
            listOf<() -> Boolean>(
                { client.assign("u", "e").variant == null },
                { client.getAssignment("u", "e").variant == null },
                { !client.trackConversion("u", "e", "purchase") }
            ).forEach { operation ->
                mockWebServer.enqueue(MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS))
                assertTrue(worker.submit<Boolean> { operation() }.get(1, TimeUnit.SECONDS))
            }
        } finally { worker.shutdownNow() }
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
        assertEquals(ResponseCode.SUCCESS.code, response.resultCode)
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
        assertEquals(SdkResponseCode.CLIENT_ERROR.code, response.resultCode)
        assertEquals("Experiment not found or not active", response.resultMessage)
    }

    @Test
    fun `assign should return error response when HTTP fails`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val response = client.assign("user123", "exp-1")

        assertEquals("user123", response.userId)
        assertEquals("exp-1", response.experimentKey)
        assertEquals(null, response.variant)
        assertEquals(SdkResponseCode.CLIENT_ERROR.code, response.resultCode)
        assert(response.resultMessage.contains("HTTP 500"))
    }

    @Test
    fun `assign should return error response when network fails`() {
        mockWebServer.shutdown()

        val response = client.assign("user123", "exp-1")

        assertEquals("user123", response.userId)
        assertEquals("exp-1", response.experimentKey)
        assertEquals(null, response.variant)
        assertEquals(SdkResponseCode.CLIENT_ERROR.code, response.resultCode)
        assert(response.resultMessage.contains("Assignment failed"))
    }

    @Test
    fun `trackConversion should send correct payload`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"userId":"user123","experimentKey":"exp-1","eventName":"purchase","variant":"A","resultCode":"0000","resultMessage":"Success"}"""))

        assertTrue(client.trackConversion("user123", "exp-1", "purchase"))

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
        assertFalse(client.trackConversion("user123", "exp-1", "purchase"))

        val request = mockWebServer.takeRequest()
        assertEquals("/v1/conversions", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun `trackConversion should not throw exception when network fails`() {
        mockWebServer.shutdown()

        // 예외가 발생하지 않아야 함
        assertFalse(client.trackConversion("user123", "exp-1", "purchase"))
    }
    @Test
    fun `HTTP success with rejected conversion returns false`() {
        mockWebServer.enqueue(MockResponse().setBody("""{"userId":"user123","experimentKey":"exp-1","eventName":"purchase","variant":null,"resultCode":"9100","resultMessage":"No prior impression"}"""))
        assertFalse(client.trackConversion("user123", "exp-1", "purchase"))
    }

    @Test
    fun `malformed conversion response fails safely`() {
        mockWebServer.enqueue(MockResponse().setBody("not-json"))
        assertFalse(client.trackConversion("user123", "exp-1", "purchase"))
    }

    @Test
    fun `lookup uses read only endpoint and encodes identifiers`() {
        mockWebServer.enqueue(MockResponse().setBody("""{"userId":"u +&","experimentKey":"e/&","variant":"A","resultCode":"0000","resultMessage":"Success"}"""))
        assertEquals("A", client.getAssignment("u +&", "e/&").variant)
        val request = mockWebServer.takeRequest()
        assertEquals("/v1/assignments", request.requestUrl!!.encodedPath)
        assertEquals("u +&", request.requestUrl!!.queryParameter("userId"))
        assertEquals("e/&", request.requestUrl!!.queryParameter("experimentKey"))
    }

}
