#!/usr/bin/env bash
# Replays a captured OCPP-J session against the running gateway, one frame at a
# time, printing each request and the backend's reply. This is the same move as
# reproducing a field issue from logged charge point traffic.
#
# Usage: ./replay.sh [session-file] [charge-point-id] [base-url]
set -euo pipefail
FILE="${1:-samples/ocpp-session.txt}"
CP="${2:-CP-REPLAY-01}"
BASE="${3:-http://localhost:8080}"

while IFS= read -r frame; do
  [[ -z "$frame" ]] && continue
  echo "-> $frame"
  echo "<- $(curl -s -X POST "$BASE/ocpp/$CP" --data-binary "$frame")"
  echo
done < "$FILE"

echo "State now:"
curl -s "$BASE/api/v1/chargepoints/$CP"; echo
