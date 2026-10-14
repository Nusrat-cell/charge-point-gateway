package com.nusrat.chargepoint;

import com.nusrat.chargepoint.metrics.MetricsRegistry;
import com.nusrat.chargepoint.model.ChargePointEvent;
import com.nusrat.chargepoint.ocpp.OcppMessageHandler;
import com.nusrat.chargepoint.queue.InMemoryEventBus;
import com.nusrat.chargepoint.state.ChargePointStateStore;
import com.nusrat.chargepoint.web.ChargePointHttpServer;

/**
 * Charge Point Event Gateway
 * -------------------------------
 * A small backend service that ingests simplified OCPP-style charge
 * point events over HTTP, processes them asynchronously through an
 * in-process event bus, keeps per-charge-point state, and exposes
 * Prometheus-compatible metrics for observability.
 *
 * This is a personal learning project, built to get hands-on with the
 * concepts behind an EV-charging backend stack (event-driven
 * processing, ordered-per-key message handling, operational metrics)
 * using only the JDK, because this build environment has no network
 * access to Maven Central / the Gradle plugin portal to pull in Spring
 * Boot or a real Kafka client. It is not a claim of production
 * experience with those exact tools - see README.md for exactly what
 * is simulated and what the real next step would be.
 */
public final class Application {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        MetricsRegistry metrics = new MetricsRegistry();
        ChargePointStateStore stateStore = new ChargePointStateStore();
        InMemoryEventBus bus = new InMemoryEventBus(4);

        metrics.registerGauge("chargepoint_charging_now", () -> (double) stateStore.countCharging());

        bus.subscribe(event -> {
            long startNanos = System.nanoTime();
            try {
                switch (event.type()) {
                    case BOOT_NOTIFICATION -> stateStore.registerBoot(event);
                    case STATUS_NOTIFICATION -> stateStore.updateStatus(event);
                    case METER_VALUES -> stateStore.recordMeterValue(event);
                    case AUTHORIZE -> System.out.printf("Authorize request from %s%n", event.chargePointId());
                }
                metrics.incrementCounter("chargepoint_events_processed", "type", event.type().name());
            } catch (Exception e) {
                metrics.incrementCounter("chargepoint_events_processing_failed");
                System.err.println("Failed to process event for " + event.chargePointId() + ": " + e.getMessage());
            } finally {
                double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                metrics.registerGauge("chargepoint_last_event_processing_ms", () -> durationMs);
            }
        });

        OcppMessageHandler ocppHandler = new OcppMessageHandler(bus, metrics);
        ChargePointHttpServer httpServer = new ChargePointHttpServer(port, bus, stateStore, metrics, ocppHandler);
        httpServer.start();

        System.out.println("Charge Point Event Gateway listening on port " + port);
        System.out.println("Try:");
        System.out.println("  curl -X POST localhost:" + port + "/api/v1/chargepoints/CP-001/events \\");
        System.out.println("    -d '{\"type\":\"STATUS_NOTIFICATION\",\"status\":\"CHARGING\",\"connectorId\":1}'");
        System.out.println("  curl localhost:" + port + "/api/v1/chargepoints");
        System.out.println("  curl localhost:" + port + "/metrics");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            bus.shutdown();
            httpServer.stop();
        }));
    }
}
