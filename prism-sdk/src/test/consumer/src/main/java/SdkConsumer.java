import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.silbaram.prism.sdk.PrismClient;
import io.github.silbaram.prism.sdk.PrismExperimentClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the published SDK's runtime dependencies, including Jackson, SLF4J and Caffeine. */
public class SdkConsumer {
    public static void main(String[] args) throws Exception {
        var exposures = new AtomicInteger();
        var conversions = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/assign", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/v1/assign")) {
                respond(exchange, 404, "{}");
                return;
            }
            exposures.incrementAndGet();
            respond(exchange, 200, """
                {"userId":"user-123","experimentKey":"checkout","variant":"A",
                 "resultCode":"0000","resultMessage":"Success"}
                """);
        });
        server.createContext("/v1/conversions", exchange -> {
            var payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!exchange.getRequestMethod().equals("POST") ||
                !payload.contains("\"userId\":\"user-123\"") ||
                !payload.contains("\"experimentKey\":\"checkout\"") ||
                !payload.contains("\"eventName\":\"purchase\"")) {
                respond(exchange, 400, "{}");
                return;
            }
            conversions.incrementAndGet();
            respond(exchange, 200, """
                {"userId":"user-123","experimentKey":"checkout","variant":"A","eventName":"purchase",
                 "resultCode":"0000","resultMessage":"Success"}
                """);
        });
        server.start();
        try {
            var transport = new PrismClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
            var client = new PrismExperimentClient(transport);
            var assignment = client.assign("user-123", "checkout");
            if (!assignment.getAssigned() || !"A".equals(assignment.getVariant())) {
                throw new AssertionError("Published SDK could not deserialize the assignment: " + assignment);
            }
            if (!client.trackIfAssigned("user-123", "checkout", "purchase")) {
                throw new AssertionError("Published SDK could not track a cached assignment");
            }
            if (exposures.get() != 1 || conversions.get() != 1) {
                throw new AssertionError("Expected one exposure and one conversion");
            }
            System.out.println("Published SDK consumer passed: assignment, cache and conversion");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
