package com.nusrat.chargepoint.model;

import java.time.Instant;

/**
 * A simplified event shape standing in for a real OCPP message. Real OCPP
 * frames are JSON-RPC-wrapped and carry many more fields; this keeps only
 * what's needed to demonstrate a realistic ingest -> process -> metrics
 * pipeline.
 */
public record ChargePointEvent(
        String chargePointId,
        int connectorId,
        ChargePointEventType type,
        ChargePointStatus status,
        Long meterValueWh,
        String errorCode,
        Instant timestamp
) {
    public ChargePointEvent withTimestampNowIfMissing() {
        return timestamp != null ? this : new ChargePointEvent(
                chargePointId, connectorId, type, status, meterValueWh, errorCode, Instant.now());
    }
}
