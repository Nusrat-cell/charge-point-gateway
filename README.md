# Charge Point Event Gateway

A small backend service that speaks **real OCPP 1.6-J** (the protocol EV
chargers use to talk to their backend), processes charge point events processes them asynchronously, keeps per-charge-point
state, and exposes Prometheus-compatible metrics — the shape of problem
behind an EV-charging backend: devices reporting status and meter
readings, a service that needs to process those events reliably and in
order, and an on-call engineer who needs to see what's happening.

## Why I built this

I built this to get hands-on with the real shape of a backend problem
in the EV charging space — event ingestion, ordered asynchronous
processing, and operational observability — rather than just reading
about it. **I'm being upfront about what this is and isn't:**

- It does **not** use a real Kafka broker. `InMemoryEventBus` is a
  hand-written in-process queue that copies the one Kafka property this
  demo actually needed — events for the same charge point are always
  processed in the order they arrived, because they're routed to the
  same worker by a hash of the charge point ID, the same idea as
  Kafka's "same key → same partition → ordered" guarantee.
- The JSON parser is hand-written too (`MiniJson`), for the same reason.
- It does **not** use Spring Boot or a real metrics library. The HTTP
  server is the JDK's built-in `com.sun.net.httpserver`, and the
  `/metrics` endpoint is hand-written — but it emits genuine
  [Prometheus exposition format](https://prometheus.io/docs/instrumenting/exposition_formats/),
  so a real Prometheus instance can scrape it with no translation
  layer (see `docker-compose.yml`).
- It's written in Java, not Kotlin. I know Java; Kotlin is next on my
  list specifically because of roles like this one.

I'd rather show working code with an honest README than overclaim
experience with tools I haven't used in production yet. The real
next step — swapping `InMemoryEventBus` for Spring Kafka, and this
HTTP server for Spring Boot — is exactly the kind of thing I want to
learn on the job.

## OCPP 1.6-J support

`POST /ocpp/{chargePointId}` takes a raw OCPP-J frame and returns the
OCPP-J reply the spec requires, echoing the request's `uniqueId`:

| Action | Backend reply |
|---|---|
| BootNotification | `Accepted` + current time + 300 s heartbeat interval |
| Heartbeat | current time |
| StatusNotification | `{}`; status mapped (Available, Preparing, Charging, SuspendedEV/EVSE, Finishing, Faulted, Unavailable); non-`NoError` error codes counted in metrics |
| MeterValues | `{}`; latest `Energy.Active.Import.Register` reading stored, kWh converted to Wh |
| Authorize | `Accepted` (demo - a real backend checks the idTag) |
| anything else | CALLERROR `NotImplemented` |
| malformed frame | CALLERROR `FormationViolation` |

Real chargers send these over one WebSocket per charge point; here they
arrive over HTTP so a captured session can be replayed from a file:

```bash
./replay.sh samples/ocpp-session.txt CP-REPLAY-01
```

`samples/ocpp-session.txt` is a full charging session - boot, authorize,
charge, meter readings, a `GroundFailure` fault, recovery - and the replay
prints each request and reply, then the charge point's final state. That
is the same loop as debugging a field issue: take the traffic, replay it,
watch what the backend does.

## Tests

`./build.sh` compiles everything and runs 14 plain-Java checks (JSON
parsing, every supported action, unit conversion, error handling, uniqueId
echo) and fails the build if any fail. No JUnit, because the project has
no external dependencies.

## Architecture

```
HTTP POST /api/v1/chargepoints/{id}/events
        │
        ▼
 ChargePointHttpServer  ──publish──▶  InMemoryEventBus (4 workers,
        │                              hashed by charge point ID
        │                              for per-device ordering)
        │                                     │
        │                                     ▼
        │                          ChargePointStateStore (in-memory,
        │                          thread-safe, per-charge-point
        │                          status + cumulative energy)
        │                                     │
        ▼                                     ▼
 GET /api/v1/chargepoints/{id}        MetricsRegistry ──▶ GET /metrics
 GET /api/v1/chargepoints                                 (Prometheus format)
```

## Run it

```bash
./build.sh            # compiles + runs the tests
PORT=8080 java -cp out com.nusrat.chargepoint.Application
./replay.sh           # replays a real OCPP session (in another terminal)
```

```bash
curl -X POST localhost:8080/api/v1/chargepoints/CP-001/events \
  -d '{"type":"BOOT_NOTIFICATION","connectorId":1}'

curl -X POST localhost:8080/api/v1/chargepoints/CP-001/events \
  -d '{"type":"STATUS_NOTIFICATION","status":"CHARGING","connectorId":1}'

curl -X POST localhost:8080/api/v1/chargepoints/CP-001/events \
  -d '{"type":"METER_VALUES","meterValueWh":1500,"connectorId":1}'

curl localhost:8080/api/v1/chargepoints/CP-001
curl localhost:8080/metrics
```

## See it in Grafana

```bash
docker compose up -d   # Prometheus on :9090, Grafana on :3000 (admin/admin)
```

Point Grafana at the Prometheus data source and graph
`chargepoint_charging_now`, `chargepoint_events_processed_total`, or
`chargepoint_last_event_processing_ms`.

## What I'd build next

- Swap `InMemoryEventBus` for real Kafka (Spring Kafka or the plain
  Java client) — the interface is already shaped around publish/
  subscribe-by-key so the swap wouldn't touch the rest of the code.
- Swap the hand-written HTTP layer for Spring Boot + Spring Web.
- Persist state to Postgres instead of an in-memory map.
- Learn Kotlin and port this to it.

## Background

I'm a final-year Computer Science student (Niels Brock / De Montfort
University, Copenhagen) interested in backend engineering at the
intersection of business and technology. My other projects —
analytics and consulting-focused — are at
[github.com/Nusrat-cell](https://github.com/Nusrat-cell).
