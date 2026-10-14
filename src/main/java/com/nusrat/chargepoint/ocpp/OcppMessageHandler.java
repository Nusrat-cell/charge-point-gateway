package com.nusrat.chargepoint.ocpp;

import com.nusrat.chargepoint.metrics.MetricsRegistry;
import com.nusrat.chargepoint.model.ChargePointEvent;
import com.nusrat.chargepoint.model.ChargePointEventType;
import com.nusrat.chargepoint.model.ChargePointStatus;
import com.nusrat.chargepoint.queue.InMemoryEventBus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles real OCPP 1.6 JSON ("OCPP-J") frames.
 *
 * An OCPP-J message is a JSON array whose first element says what kind of
 * message it is:
 *
 *   CALL        [2, "<uniqueId>", "<Action>", {payload}]   charge point -> backend
 *   CALLRESULT  [3, "<uniqueId>", {payload}]                backend -> charge point
 *   CALLERROR   [4, "<uniqueId>", "<errorCode>", "<description>", {details}]
 *
 * The backend must answer every CALL with a CALLRESULT or CALLERROR that
 * echoes the same uniqueId - that's how the charge point matches answers
 * to requests.
 *
 * Supported actions: BootNotification, Heartbeat, StatusNotification,
 * MeterValues, Authorize. Anything else gets a CALLERROR "NotImplemented",
 * which is what the spec says to do.
 *
 * In production OCPP-J travels over a WebSocket per charge point. Here the
 * frame arrives in an HTTP POST body so it can be replayed from a file with
 * curl - which is also how you'd reproduce a field issue from captured
 * traffic.
 */
public final class OcppMessageHandler {

    public static final int CALL = 2;
    public static final int CALLRESULT = 3;
    public static final int CALLERROR = 4;

    private static final int HEARTBEAT_INTERVAL_SECONDS = 300;

    private final InMemoryEventBus bus;
    private final MetricsRegistry metrics;

    public OcppMessageHandler(InMemoryEventBus bus, MetricsRegistry metrics) {
        this.bus = bus;
        this.metrics = metrics;
    }

    /** Takes one raw OCPP-J frame, returns the raw OCPP-J reply. */
    public String handle(String chargePointId, String rawFrame) {
        String uniqueId = "unknown";
        try {
            Object parsed = MiniJson.parse(rawFrame);
            if (!(parsed instanceof List<?> frame) || frame.size() < 3) {
                return callError(uniqueId, "FormationViolation", "Frame must be a JSON array");
            }
            if (!(frame.get(0) instanceof Number type) || type.intValue() != CALL) {
                return callError(uniqueId, "FormationViolation", "Backend only accepts CALL (type 2) frames here");
            }
            uniqueId = String.valueOf(frame.get(1));
            if (frame.size() != 4 || !(frame.get(2) instanceof String action)
                    || !(frame.get(3) instanceof Map<?, ?> payload)) {
                return callError(uniqueId, "FormationViolation", "CALL must be [2, uniqueId, action, {payload}]");
            }

            metrics.incrementCounter("ocpp_calls_received", "action", action);
            return switch (action) {
                case "BootNotification" -> bootNotification(chargePointId, uniqueId, payload);
                case "Heartbeat" -> callResult(uniqueId, Map.of("currentTime", Instant.now().toString()));
                case "StatusNotification" -> statusNotification(chargePointId, uniqueId, payload);
                case "MeterValues" -> meterValues(chargePointId, uniqueId, payload);
                case "Authorize" -> authorize(chargePointId, uniqueId);
                default -> callError(uniqueId, "NotImplemented", "Action not supported: " + action);
            };
        } catch (IllegalArgumentException e) {
            metrics.incrementCounter("ocpp_frames_rejected");
            return callError(uniqueId, "FormationViolation", e.getMessage());
        }
    }

    private String bootNotification(String cpId, String uniqueId, Map<?, ?> payload) {
        requireString(payload, "chargePointVendor");
        requireString(payload, "chargePointModel");
        bus.publish(new ChargePointEvent(cpId, 0, ChargePointEventType.BOOT_NOTIFICATION,
                null, null, null, Instant.now()));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("currentTime", Instant.now().toString());
        reply.put("interval", HEARTBEAT_INTERVAL_SECONDS);
        reply.put("status", "Accepted");
        return callResult(uniqueId, reply);
    }

    private String statusNotification(String cpId, String uniqueId, Map<?, ?> payload) {
        int connectorId = requireInt(payload, "connectorId");
        String errorCode = requireString(payload, "errorCode");
        ChargePointStatus status = mapStatus(requireString(payload, "status"));
        if (!"NoError".equals(errorCode)) {
            metrics.incrementCounter("ocpp_connector_errors", "errorCode", errorCode);
        }
        bus.publish(new ChargePointEvent(cpId, connectorId, ChargePointEventType.STATUS_NOTIFICATION,
                status, null, "NoError".equals(errorCode) ? null : errorCode, Instant.now()));
        return callResult(uniqueId, Map.of());
    }

    private String meterValues(String cpId, String uniqueId, Map<?, ?> payload) {
        int connectorId = requireInt(payload, "connectorId");
        Object mv = payload.get("meterValue");
        if (!(mv instanceof List<?> meterValueList) || meterValueList.isEmpty()) {
            throw new IllegalArgumentException("meterValue must be a non-empty array");
        }
        Long energyWh = latestEnergyWh(meterValueList);
        if (energyWh != null) {
            bus.publish(new ChargePointEvent(cpId, connectorId, ChargePointEventType.METER_VALUES,
                    null, energyWh, null, Instant.now()));
        }
        return callResult(uniqueId, Map.of());
    }

    private String authorize(String cpId, String uniqueId) {
        bus.publish(new ChargePointEvent(cpId, 0, ChargePointEventType.AUTHORIZE,
                null, null, null, Instant.now()));
        // Demo only: accepts every idTag. A real backend checks it against a user/RFID database.
        return callResult(uniqueId, Map.of("idTagInfo", Map.of("status", "Accepted")));
    }

    /**
     * OCPP sends readings as meterValue[] -> sampledValue[]. Picks the last
     * "Energy.Active.Import.Register" reading (the default measurand when none
     * is given) and converts kWh to Wh if needed.
     */
    static Long latestEnergyWh(List<?> meterValueList) {
        Long result = null;
        for (Object mvObj : meterValueList) {
            if (!(mvObj instanceof Map<?, ?> mv) || !(mv.get("sampledValue") instanceof List<?> samples)) continue;
            for (Object sObj : samples) {
                if (!(sObj instanceof Map<?, ?> sample)) continue;
                Object measurand = sample.get("measurand");
                if (measurand != null && !"Energy.Active.Import.Register".equals(measurand)) continue;
                Object value = sample.get("value"); // OCPP sends values as strings, e.g. "1520.5"
                if (value == null) continue;
                double v = Double.parseDouble(String.valueOf(value));
                if ("kWh".equals(sample.get("unit"))) v *= 1000;
                result = Math.round(v);
            }
        }
        return result;
    }

    /** OCPP 1.6 ChargePointStatus strings -> our enum. */
    static ChargePointStatus mapStatus(String ocppStatus) {
        return switch (ocppStatus) {
            case "Available" -> ChargePointStatus.AVAILABLE;
            case "Preparing" -> ChargePointStatus.PREPARING;
            case "Charging" -> ChargePointStatus.CHARGING;
            case "SuspendedEV", "SuspendedEVSE" -> ChargePointStatus.SUSPENDED_EV;
            case "Finishing" -> ChargePointStatus.FINISHING;
            case "Faulted" -> ChargePointStatus.FAULTED;
            case "Unavailable", "Reserved" -> ChargePointStatus.UNAVAILABLE;
            default -> throw new IllegalArgumentException("Unknown OCPP status: " + ocppStatus);
        };
    }

    private static String requireString(Map<?, ?> payload, String field) {
        if (!(payload.get(field) instanceof String v)) {
            throw new IllegalArgumentException("Missing or non-string field: " + field);
        }
        return v;
    }

    private static int requireInt(Map<?, ?> payload, String field) {
        if (!(payload.get(field) instanceof Number n)) {
            throw new IllegalArgumentException("Missing or non-numeric field: " + field);
        }
        return n.intValue();
    }

    static String callResult(String uniqueId, Map<String, ?> payload) {
        return MiniJson.write(List.of(CALLRESULT, uniqueId, payload));
    }

    static String callError(String uniqueId, String code, String description) {
        return MiniJson.write(List.of(CALLERROR, uniqueId, code, description, Map.of()));
    }
}
