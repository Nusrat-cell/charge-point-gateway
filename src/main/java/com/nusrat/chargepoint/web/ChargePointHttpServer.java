package com.nusrat.chargepoint.web;

import com.nusrat.chargepoint.metrics.MetricsRegistry;
import com.nusrat.chargepoint.model.*;
import com.nusrat.chargepoint.ocpp.OcppMessageHandler;
import com.nusrat.chargepoint.queue.InMemoryEventBus;
import com.nusrat.chargepoint.state.ChargePointSnapshot;
import com.nusrat.chargepoint.state.ChargePointStateStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plain HTTP server built on the JDK's built-in com.sun.net.httpserver
 * (no web framework dependency, since this environment can't pull
 * Spring from Maven Central). Routes:
 *
 *   POST /api/v1/chargepoints/{id}/events  - ingest an event
 *   GET  /api/v1/chargepoints/{id}         - read current state
 *   GET  /api/v1/chargepoints              - list all charge points
 *   GET  /metrics                          - Prometheus scrape target
 *   POST /ocpp/{id}                        - real OCPP 1.6-J frame in, OCPP-J reply out
 *
 * In a real OCPP deployment, charge points talk to the backend over a
 * persistent WebSocket, not plain REST - a REST endpoint is used here so
 * the whole thing is reachable with one curl command for the demo.
 */
public final class ChargePointHttpServer {

    private static final Pattern EVENT_PATH = Pattern.compile("^/api/v1/chargepoints/([^/]+)/events$");
    private static final Pattern GET_PATH = Pattern.compile("^/api/v1/chargepoints/([^/]+)$");

    private final HttpServer server;

    public ChargePointHttpServer(int port,
                                  InMemoryEventBus bus,
                                  ChargePointStateStore stateStore,
                                  MetricsRegistry metrics,
                                  OcppMessageHandler ocppHandler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/v1/chargepoints", exchange -> {
            try {
                handleChargePoints(exchange, bus, stateStore, metrics);
            } catch (Exception e) {
                respond(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        server.createContext("/metrics", exchange ->
                respond(exchange, 200, metrics.scrape()));

        // Real OCPP 1.6-J frames: POST /ocpp/{chargePointId} with body [2,"id","Action",{...}]
        server.createContext("/ocpp/", exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/ocpp/".length());
            if (!"POST".equals(exchange.getRequestMethod()) || id.isEmpty() || id.contains("/")) {
                respond(exchange, 404, "{\"error\":\"use POST /ocpp/{chargePointId}\"}");
                return;
            }
            String frame = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, ocppHandler.handle(id, frame));
        });

        server.setExecutor(null);
    }

    private void handleChargePoints(HttpExchange exchange, InMemoryEventBus bus,
                                     ChargePointStateStore stateStore, MetricsRegistry metrics) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        Matcher eventMatcher = EVENT_PATH.matcher(path);
        if ("POST".equals(method) && eventMatcher.matches()) {
            String chargePointId = eventMatcher.group(1);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ChargePointEvent event = SimpleJson.parseEvent(chargePointId, body);
            bus.publish(event);
            metrics.incrementCounter("chargepoint_events_published", "type", event.type().name());
            respond(exchange, 202, "{\"accepted\":true}");
            return;
        }

        Matcher getMatcher = GET_PATH.matcher(path);
        if ("GET".equals(method) && getMatcher.matches()) {
            ChargePointSnapshot snap = stateStore.find(getMatcher.group(1));
            if (snap == null) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
            } else {
                respond(exchange, 200, snap.toJson());
            }
            return;
        }

        if ("GET".equals(method) && path.equals("/api/v1/chargepoints")) {
            List<String> jsonList = stateStore.all().stream().map(ChargePointSnapshot::toJson).toList();
            respond(exchange, 200, "[" + String.join(",", jsonList) + "]");
            return;
        }

        respond(exchange, 404, "{\"error\":\"no such route\"}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    /** Tiny hand-rolled JSON reader - just enough for this demo's flat event shape. */
    static final class SimpleJson {
        static ChargePointEvent parseEvent(String chargePointId, String body) {
            String type = extract(body, "type");
            String statusStr = extract(body, "status");
            String meterStr = extract(body, "meterValueWh");
            String errorCode = extract(body, "errorCode");
            int connectorId = parseIntOrDefault(extract(body, "connectorId"), 1);

            return new ChargePointEvent(
                    chargePointId,
                    connectorId,
                    ChargePointEventType.valueOf(type),
                    statusStr == null ? null : ChargePointStatus.valueOf(statusStr),
                    meterStr == null ? null : Long.parseLong(meterStr),
                    errorCode,
                    Instant.now()
            );
        }

        private static int parseIntOrDefault(String s, int def) {
            try {
                return s == null ? def : Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static String extract(String json, String field) {
            Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?([^\",}]+)\"?");
            Matcher m = p.matcher(json);
            return m.find() ? m.group(1).trim() : null;
        }
    }
}
