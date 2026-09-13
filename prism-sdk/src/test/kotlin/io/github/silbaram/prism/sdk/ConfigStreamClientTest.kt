package io.github.silbaram.prism.sdk

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ConfigStreamClientTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition())
    }

    @Test fun `empty successful connections increase reconnect backoff until a configuration arrives`() {
        val requests = CopyOnWriteArrayList<Long>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(System.nanoTime())
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("")
                }
            }
            server.start()
            HttpClient.newHttpClient().use { http ->
                ConfigStreamClient(server.url("/").toString().trimEnd('/'), Duration.ofSeconds(1), null, http) {}.use {
                    await { requests.size >= 3 }
                    assertTrue(requests[1] - requests[0] >= TimeUnit.MILLISECONDS.toNanos(850))
                    assertTrue(requests[2] - requests[1] >= TimeUnit.MILLISECONDS.toNanos(1800))
                }
            }
        }
    }

    @Test fun `reconnection authenticates again and installs the next complete event`() {
        val installed = CopyOnWriteArrayList<String>()
        val key = "prism-stream-test-key-0123456789abcdef"
        MockWebServer().use { server ->
            for (value in listOf("first", "second")) server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: config\r\ndata: $value\r\n\r\n"))
            server.start()
            HttpClient.newHttpClient().use { http ->
                ConfigStreamClient(server.url("/").toString().trimEnd('/'), Duration.ofSeconds(1), key, http) { installed.add(it) }.use {
                    await { installed.size == 2 }
                    assertEquals(listOf("first", "second"), installed)
                    repeat(2) {
                        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                        assertEquals("/v1/config/stream", request.path)
                        assertEquals(key, request.getHeader("X-Prism-Api-Key"))
                    }
                }
            }
        }
    }
}
