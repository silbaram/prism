import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.silbaram.prism.sdk.PrismClient;
import io.github.silbaram.prism.sdk.PrismExperimentClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

/** Independently resolves core + SpEL through both POM and Gradle module metadata. */
public class SdkConsumer {
    public static void main(String[] args) throws Exception {
        var exposures = new AtomicInteger();
        var conversions = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/config", exchange -> respond(exchange, 200, """
            {"version":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "experiments":[{"key":"checkout","status":"ACTIVE","variants":[{"name":"A","weight":100}],
             "targetingRules":["age >= 20 && country == 'KR'"]}]}
            """));
        server.createContext("/v1/events", exchange -> {
            var payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exposures.addAndGet((int) Pattern.compile("\"type\":\"exposure\"").matcher(payload).results().count());
            conversions.addAndGet((int) Pattern.compile("\"type\":\"conversion\"").matcher(payload).results().count());
            var results = new StringJoiner(",", "{\"results\":[", "]}");
            var ids = Pattern.compile("\"eventId\":\"([^\"]+)\"").matcher(payload);
            while (ids.find()) {
                results.add("{\"eventId\":\"" + ids.group(1) + "\",\"status\":\"ACCEPTED\"}");
            }
            respond(exchange, 200, results.toString());
        });
        server.start();
        try (var transport = new PrismClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5))) {
            var client = new PrismExperimentClient(transport);
            var assignment = client.assign("user-123", "checkout", Map.of("age", 25, "country", "KR"));
            if (!assignment.getAssigned() || !"A".equals(assignment.getVariant()) || assignment.getExposureEventId() == null) {
                throw new AssertionError("Published SDK local evaluation failed: " + assignment);
            }
            if (!client.track(assignment, "purchase") || !transport.flush()) {
                throw new AssertionError("Published SDK event delivery failed");
            }
            if (client.assign("too-young", "checkout", Map.of("age", 10, "country", "KR")).getAssigned()) {
                throw new AssertionError("Targeting was not applied");
            }
            if (exposures.get() != 1 || conversions.get() != 1) {
                throw new AssertionError("Expected one exposure and one conversion");
            }
            System.out.println("Published SDK consumer passed: local evaluation, SpEL and batch events");
        } finally {
            server.stop(0);
        }
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
