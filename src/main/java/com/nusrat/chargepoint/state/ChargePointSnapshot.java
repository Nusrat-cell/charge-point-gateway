package com.nusrat.chargepoint.state;

import com.nusrat.chargepoint.model.ChargePointStatus;

import java.time.Instant;

public final class ChargePointSnapshot {
    public final String chargePointId;
    public volatile ChargePointStatus status;
    public volatile Instant lastSeen;
    public volatile long totalEnergyWh;

    public ChargePointSnapshot(String chargePointId) {
        this.chargePointId = chargePointId;
        this.status = ChargePointStatus.UNAVAILABLE;
        this.lastSeen = Instant.now();
        this.totalEnergyWh = 0;
    }

    public String toJson() {
        return "{\"chargePointId\":\"" + chargePointId + "\"," +
                "\"status\":\"" + status + "\"," +
                "\"lastSeen\":\"" + lastSeen + "\"," +
                "\"totalEnergyWh\":" + totalEnergyWh + "}";
    }
}
