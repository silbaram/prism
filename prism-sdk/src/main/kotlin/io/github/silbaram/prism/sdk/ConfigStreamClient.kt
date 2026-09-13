package io.github.silbaram.prism.sdk

import java.io.InputStream
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Streaming accelerates updates; ordinary conditional polling remains the recovery path. */
internal class ConfigStreamClient(baseUrl: String, private val timeout: Duration, private val apiKey: String?,
                                  private val http: HttpClient, private val install: (String) -> Unit) : AutoCloseable {
    private val uri = URI.create("$baseUrl/v1/config/stream")
    private val closed = AtomicBoolean()
    private val body = AtomicReference<InputStream?>()
    private val pending = AtomicReference<CompletableFuture<HttpResponse<InputStream>>?>()
    private val lastRead = AtomicLong(System.nanoTime())
    private val worker = Executors.newScheduledThreadPool(2) { Thread(it, "prism-config-stream").apply { isDaemon = true } }
    init {
        worker.execute { listen() }
        worker.scheduleWithFixedDelay({
            if (System.nanoTime() - lastRead.get() > TimeUnit.SECONDS.toNanos(45)) {
                try { body.getAndSet(null)?.close() } catch (_: Exception) { }
            }
        }, 15, 15, TimeUnit.SECONDS)
    }
    private fun listen() {
        var retrySeconds = 1L
        while (!closed.get()) {
            try {
                val request = HttpRequest.newBuilder(uri).header("Accept", "text/event-stream").GET()
                apiKey?.let { request.header("X-Prism-Api-Key", it) }
                val future = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                pending.set(future)
                val response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
                body.set(response.body())
                check(response.statusCode() == 200 && response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))
                lastRead.set(System.nanoTime())
                response.body().bufferedReader(Charsets.UTF_8).use { reader ->
                    val lines = BoundedSseReader(reader, 16 * 1024 * 1024)
                    val data = StringBuilder()
                    var event = ""
                    while (!closed.get()) {
                        val line = lines.readLine() ?: break
                        lastRead.set(System.nanoTime())
                        if (line.isEmpty()) {
                            if (event == "config" && data.isNotEmpty()) { install(data.toString()); retrySeconds = 1 }
                            data.setLength(0); event = ""
                        } else if (line.startsWith("event:")) event = line.substringAfter(':').trim()
                        else if (line.startsWith("data:")) {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substringAfter(':').removePrefix(" "))
                            check(data.length <= 16 * 1024 * 1024) { "Configuration stream frame too large" }
                        }
                    }
                }
            } catch (_: Exception) { /* Polling retains and refreshes the last valid configuration. */ }
            finally {
                pending.getAndSet(null)?.cancel(true)
                try { body.getAndSet(null)?.close() } catch (_: Exception) { }
            }
            if (!closed.get()) try { TimeUnit.SECONDS.sleep(retrySeconds) } catch (_: InterruptedException) { return }
            retrySeconds = (retrySeconds * 2).coerceAtMost(30)
        }
    }
    override fun close() {
        closed.set(true)
        pending.getAndSet(null)?.cancel(true)
        try { body.getAndSet(null)?.close() } catch (_: Exception) { }
        worker.shutdownNow()
    }
}
