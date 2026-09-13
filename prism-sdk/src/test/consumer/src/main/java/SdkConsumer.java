import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.silbaram.prism.sdk.PrismClient;
import io.github.silbaram.prism.sdk.FileStickyAssignmentStore;
import java.nio.file.Files;
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
        var population = new AtomicInteger();
        var apiKey = "consumer-test-api-key-0123456789abcdef";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/config", exchange -> {
            if (!apiKey.equals(exchange.getRequestHeaders().getFirst("X-Prism-Api-Key"))) {
                respond(exchange, 401, "{}"); return;
            }
            respond(exchange, 200, """
            {"version":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "holdout":{"key":"permanent","basisPoints":0,"configured":true}, "revision":1,
             "experiments":[{"key":"checkout","status":"ACTIVE","variants":[{"name":"A","weight":100}],
             "targetingRules":["age >= 20 && country == 'KR'"], "trafficAllocation":100, "stickyBucketing":true, "layer":{"key":"checkout","start":0,"end":10000}},
             {"key":"disabled","status":"ACTIVE","variants":[{"name":"A","weight":100}],"trafficAllocation":0},
             {"key":"expired","status":"ACTIVE","variants":[{"name":"A","weight":100}],"endsAt":"2020-01-01T00:00:00Z"}]}
            """);
        });
        server.createContext("/v1/events", exchange -> {
            if (!apiKey.equals(exchange.getRequestHeaders().getFirst("X-Prism-Api-Key"))) {
                respond(exchange, 401, "{}"); return;
            }
            var payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exposures.addAndGet((int) Pattern.compile("\"type\":\"exposure\"").matcher(payload).results().count());
            conversions.addAndGet((int) Pattern.compile("\"type\":\"conversion\"").matcher(payload).results().count());
            population.addAndGet((int) Pattern.compile("population_(exposure|conversion)").matcher(payload).results().count());
            var results = new StringJoiner(",", "{\"results\":[", "]}");
            var ids = Pattern.compile("\"eventId\":\"([^\"]+)\"").matcher(payload);
            while (ids.find()) {
                results.add("{\"eventId\":\"" + ids.group(1) + "\",\"status\":\"ACCEPTED\"}");
            }
            respond(exchange, 200, results.toString());
        });
        server.start();
        try (var transport = new PrismClient("http://127.0.0.1:" + server.getAddress().getPort(), apiKey, Duration.ofSeconds(5))) {
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
            if (client.assign("u", "disabled").getAssigned() || client.assign("u", "expired").getAssigned()) {
                throw new AssertionError("Participation or period was not enforced by the published SDK");
            }
            if (!Boolean.FALSE.equals(client.isInHoldout("user-123")) ||
                !client.recordPopulationExposure("user-123") || !client.trackPopulationConversion("user-123", "purchase") ||
                !transport.flush() || population.get() != 2) {
                throw new AssertionError("Population instrumentation failed");
            }
            var directory = Files.createTempDirectory("prism-consumer-sticky");
            try {
                new FileStickyAssignmentStore(directory).getOrPut("u", "checkout", "A");
                if (!"A".equals(new FileStickyAssignmentStore(directory).getOrPut("u", "checkout", "B"))) {
                    throw new AssertionError("Published durable sticky assignment failed");
                }
            } finally {
                try (var files = Files.list(directory)) { for (var file : files.toList()) Files.delete(file); }
                Files.delete(directory);
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
