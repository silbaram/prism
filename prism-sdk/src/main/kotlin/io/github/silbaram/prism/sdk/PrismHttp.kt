package io.github.silbaram.prism.sdk

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** HttpRequest.timeout alone does not bound every stalled response-body path on JDK 21. */
internal fun sendWithTimeout(http: HttpClient, request: HttpRequest, budget: Duration): HttpResponse<String> {
    val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    return try { future.get(budget.toNanos(), TimeUnit.NANOSECONDS) }
    catch (exception: Exception) { future.cancel(true); throw exception }
}
