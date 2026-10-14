package com.nusrat.chargepoint.state;

import com.nusrat.chargepoint.model.ChargePointEvent;
import com.nusrat.chargepoint.model.ChargePointStatus;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state store standing in for what would be a real database
 * (Postgres, DynamoDB, ...) in production. Tracks the latest known
 * status and cumulative energy per charge point - the kind of thing an
 * on-call engineer would check on a dashboard while debugging a field
 * issue.
 */
public final class ChargePointStateStore {

    private final ConcurrentHashMap<String, ChargePointSnapshot> snapshots = new ConcurrentHashMap<>();

    public void registerBoot(ChargePointEvent event) {
        snapshot(event.chargePointId()).lastSeen = event.timestamp();
    }

    public void updateStatus(ChargePointEvent event) {
        if (event.status() == null) return;
        ChargePointSnapshot snap = snapshot(event.chargePointId());
        snap.status = event.status();
        snap.lastSeen = event.timestamp();
    }

    public void recordMeterValue(ChargePointEvent event) {
        if (event.meterValueWh() == null) return;
        ChargePointSnapshot snap = snapshot(event.chargePointId());
        snap.totalEnergyWh = event.meterValueWh();
        snap.lastSeen = event.timestamp();
    }

    public ChargePointSnapshot snapshot(String chargePointId) {
        return snapshots.computeIfAbsent(chargePointId, ChargePointSnapshot::new);
    }

    public ChargePointSnapshot find(String chargePointId) {
        return snapshots.get(chargePointId);
    }

    public Collection<ChargePointSnapshot> all() {
        return snapshots.values();
    }

    public long countCharging() {
        return snapshots.values().stream()
                .filter(s -> s.status == ChargePointStatus.CHARGING)
                .count();
    }
}
