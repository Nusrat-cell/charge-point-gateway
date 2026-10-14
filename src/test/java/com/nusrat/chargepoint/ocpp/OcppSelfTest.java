package com.nusrat.chargepoint.ocpp;

import com.nusrat.chargepoint.metrics.MetricsRegistry;
import com.nusrat.chargepoint.model.ChargePointEvent;
import com.nusrat.chargepoint.model.ChargePointStatus;
import com.nusrat.chargepoint.queue.InMemoryEventBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Plain-Java tests (no JUnit - this project has zero dependencies).
 * Run with ./build.sh, which fails if any check fails.
 */
public final class OcppSelfTest {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        jsonRoundTrip();
        jsonRejectsGarbage();
        bootNotificationIsAccepted();
        statusNotificationPublishesEvent();
        faultedConnectorCountsErrorCode();
        meterValuesConvertsKwhToWh();
        unknownActionReturnsNotImplemented();
        malformedFrameReturnsFormationViolation();
        uniqueIdIsEchoed();
        System.out.println("All " + passed + " OCPP checks passed.");
    }

    // ---- MiniJson ----

    static void jsonRoundTrip() {
        String in = "[2,\"abc\",\"StatusNotification\",{\"connectorId\":1,\"status\":\"Charging\",\"ok\":true,\"x\":null,\"n\":-1.5}]";
        Object parsed = MiniJson.parse(in);
        check(MiniJson.write(parsed).equals(in), "JSON parse -> write round trip is lossless");
    }

    static void jsonRejectsGarbage() {
        boolean threw = false;
        try { MiniJson.parse("[2, \"abc\", "); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "Truncated JSON is rejected");
    }

    // ---- OCPP handler ----

    static void bootNotificationIsAccepted() {
        Harness h = new Harness();
        List<?> reply = h.send("[2,\"1\",\"BootNotification\",{\"chargePointVendor\":\"Zaptec\",\"chargePointModel\":\"Pro\"}]");
        check(((Number) reply.get(0)).intValue() == 3, "BootNotification gets a CALLRESULT");
        Map<?, ?> payload = (Map<?, ?>) reply.get(2);
        check("Accepted".equals(payload.get("status")), "BootNotification status is Accepted");
        check(((Number) payload.get("interval")).intValue() == 300, "Heartbeat interval is returned");
    }

    static void statusNotificationPublishesEvent() throws InterruptedException {
        Harness h = new Harness();
        h.send("[2,\"2\",\"StatusNotification\",{\"connectorId\":1,\"errorCode\":\"NoError\",\"status\":\"Charging\"}]");
        ChargePointEvent e = h.awaitEvent();
        check(e != null && e.status() == ChargePointStatus.CHARGING, "StatusNotification Charging -> CHARGING event");
        check(e != null && e.connectorId() == 1, "Connector id is carried through");
    }

    static void faultedConnectorCountsErrorCode() throws InterruptedException {
        Harness h = new Harness();
        h.send("[2,\"3\",\"StatusNotification\",{\"connectorId\":2,\"errorCode\":\"GroundFailure\",\"status\":\"Faulted\"}]");
        ChargePointEvent e = h.awaitEvent();
        check(e != null && e.status() == ChargePointStatus.FAULTED, "Faulted status is mapped");
        check(e != null && "GroundFailure".equals(e.errorCode()), "Error code is kept for debugging");
        check(h.metrics.scrape().contains("ocpp_connector_errors_total{errorCode=\"GroundFailure\"} 1"),
                "Connector error shows up in metrics");
    }

    static void meterValuesConvertsKwhToWh() throws InterruptedException {
        Harness h = new Harness();
        h.send("[2,\"4\",\"MeterValues\",{\"connectorId\":1,\"meterValue\":[{\"timestamp\":\"2026-10-14T10:00:00Z\","
                + "\"sampledValue\":[{\"value\":\"12.5\",\"measurand\":\"Energy.Active.Import.Register\",\"unit\":\"kWh\"}]}]}]");
        ChargePointEvent e = h.awaitEvent();
        check(e != null && e.meterValueWh() == 12500L, "12.5 kWh is stored as 12500 Wh");
    }

    static void unknownActionReturnsNotImplemented() {
        Harness h = new Harness();
        List<?> reply = h.send("[2,\"5\",\"FirmwareStatusNotification\",{\"status\":\"Installed\"}]");
        check(((Number) reply.get(0)).intValue() == 4 && "NotImplemented".equals(reply.get(2)),
                "Unsupported action -> CALLERROR NotImplemented");
    }

    static void malformedFrameReturnsFormationViolation() {
        Harness h = new Harness();
        List<?> reply = h.send("[2,\"6\",\"StatusNotification\",{\"connectorId\":1}]");
        check(((Number) reply.get(0)).intValue() == 4 && "FormationViolation".equals(reply.get(2)),
                "Missing required fields -> CALLERROR FormationViolation");
    }

    static void uniqueIdIsEchoed() {
        Harness h = new Harness();
        List<?> reply = h.send("[2,\"msg-42\",\"Heartbeat\",{}]");
        check("msg-42".equals(reply.get(1)), "Reply echoes the request's uniqueId");
    }

    // ---- helpers ----

    static final class Harness {
        final MetricsRegistry metrics = new MetricsRegistry();
        final InMemoryEventBus bus = new InMemoryEventBus(1);
        final List<ChargePointEvent> events = new CopyOnWriteArrayList<>();
        final OcppMessageHandler handler = new OcppMessageHandler(bus, metrics);

        Harness() {
            bus.subscribe(events::add);
        }

        List<?> send(String frame) {
            return (List<?>) MiniJson.parse(handler.handle("CP-TEST", frame));
        }

        ChargePointEvent awaitEvent() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (events.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            bus.shutdown();
            return events.isEmpty() ? null : events.get(0);
        }
    }

    static void check(boolean condition, String name) {
        if (!condition) {
            System.err.println("FAIL: " + name);
            System.exit(1);
        }
        passed++;
        System.out.println("ok   " + name);
    }
}
