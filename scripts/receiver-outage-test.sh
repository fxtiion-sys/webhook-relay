
#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RECEIVER_PORT="${RECEIVER_PORT:-8091}"
RECEIVER_URL="http://127.0.0.1:${RECEIVER_PORT}"
EVENT_COUNT=10
EVENT_TYPE="outage.test.$(uuidgen)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RECEIVER_PID=""
WORK_DIR=$(mktemp -d)
RECEIVER_LOG="${WORK_DIR}/receiver.log"
EVENT_IDS="${WORK_DIR}/events.txt"
DELIVERY_IDS="${WORK_DIR}/deliveries.txt"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

cleanup() {
    if [[ -n "$RECEIVER_PID" ]]; then
        kill "$RECEIVER_PID" 2>/dev/null || true
        wait "$RECEIVER_PID" 2>/dev/null || true
    fi

    echo "Evidence directory: ${WORK_DIR}"
}

trap cleanup EXIT

for cmd in curl jq python3 uuidgen; do
    command -v "$cmd" >/dev/null || fail "$cmd is required"
done

start_receiver() {
    python3 -u "${SCRIPT_DIR}/outage-receiver.py" \
        --port "$RECEIVER_PORT" \
        >> "$RECEIVER_LOG" 2>&1 &

    RECEIVER_PID=$!

    for ((i=0; i<30; i++)); do
        kill -0 "$RECEIVER_PID" 2>/dev/null \
            || fail "Receiver process exited"

        if curl -fsS --max-time 1 \
            "${RECEIVER_URL}/health" >/dev/null 2>&1; then
            return 0
        fi

        sleep 0.1
    done

    fail "Receiver failed to start"
}

stop_receiver() {
    kill "$RECEIVER_PID"
    wait "$RECEIVER_PID" 2>/dev/null || true
    RECEIVER_PID=""

    if curl -fsS --max-time 1 \
        "${RECEIVER_URL}/health" >/dev/null 2>&1; then
        fail "Receiver is still available"
    fi
}

get_deliveries() {
    curl -fsS --max-time 5 \
        "${BASE_URL}/deliveries?endpointId=${endpoint_id}&size=100"
}

echo "=== Receiver Outage Recovery Test ==="

curl -fsS --max-time 5 \
    "${BASE_URL}/actuator/health" |
    jq -e '.status == "UP"' >/dev/null \
    || fail "Webhook Relay is not healthy"

if curl -fsS --max-time 1 \
    "${RECEIVER_URL}/health" >/dev/null 2>&1; then
    fail "Receiver port is already in use"
fi

echo "[1/7] Starting independent receiver..."

start_receiver

echo "[2/7] Registering endpoint..."

endpoint_response=$(curl -fsS \
    -X POST "${BASE_URL}/endpoints" \
    -H "Content-Type: application/json" \
    -d "$(jq -n \
        --arg url "${RECEIVER_URL}/webhook" \
        --arg type "$EVENT_TYPE" \
        '{url: $url, eventTypes: [$type]}')")

endpoint_id=$(echo "$endpoint_response" |
    jq -er '.id')

echo "Endpoint ID: $endpoint_id"

echo "[3/7] Stopping receiver..."

stop_receiver

echo "Receiver is unavailable"

echo "[4/7] Publishing ${EVENT_COUNT} events..."

for ((i=1; i<=EVENT_COUNT; i++)); do

    response=$(curl -fsS \
        -X POST "${BASE_URL}/events" \
        -H "Content-Type: application/json" \
        -d "$(jq -n \
            --arg type "$EVENT_TYPE" \
            --argjson orderId "$i" \
            '{type: $type, payload: {
                orderId: $orderId,
                source: "outage-test"
            }}')")

    event_id=$(echo "$response" | jq -er '.eventId')

    echo "$event_id" >> "$EVENT_IDS"
    echo "Accepted event $i: $event_id"

done

echo "[5/7] Verifying failed delivery attempts..."

outage_verified=false

for ((i=0; i<40; i++)); do

    deliveries=$(get_deliveries)

    failed_count=$(echo "$deliveries" | jq '
        [.content[] |
        select(.status == "PENDING"
            and .attemptCount >= 1)] |
        length
    ')

    dead_count=$(echo "$deliveries" | jq '
        [.content[] |
        select(.status == "DEAD")] |
        length
    ')

    [[ "$dead_count" -eq 0 ]] \
        || fail "Delivery exhausted retry budget"

    if [[ "$failed_count" -eq "$EVENT_COUNT" ]]; then
        outage_verified=true
        echo "$deliveries" > "${WORK_DIR}/during-outage.json"
        break
    fi

    sleep 0.1
done

[[ "$outage_verified" == "true" ]] \
    || fail "Could not verify all failed attempts"

echo "All deliveries retained for retry"

echo "[6/7] Restoring receiver..."

start_receiver

recovered=false

for ((i=0; i<60; i++)); do

    deliveries=$(get_deliveries)

    succeeded=$(echo "$deliveries" | jq '
        [.content[] |
        select(.status == "SUCCEEDED")] |
        length
    ')

    dead=$(echo "$deliveries" | jq '
        [.content[] |
        select(.status == "DEAD")] |
        length
    ')

    echo "Recovered: ${succeeded}/${EVENT_COUNT}"

    [[ "$dead" -eq 0 ]] \
        || fail "Dead delivery detected"

    if [[ "$succeeded" -eq "$EVENT_COUNT" ]]; then
        recovered=true
        break
    fi

    sleep 1
done

[[ "$recovered" == "true" ]] \
    || fail "Recovery timed out"

echo "[7/7] Verifying zero missing deliveries..."

deliveries=$(get_deliveries)

echo "$deliveries" > "${WORK_DIR}/after-recovery.json"

total=$(echo "$deliveries" | jq '.totalElements')

[[ "$total" -eq "$EVENT_COUNT" ]] \
    || fail "Unexpected delivery count"

echo "$deliveries" |
    jq -r '.content[].eventId' |
    sort > "${WORK_DIR}/delivered-events.txt"

sort "$EVENT_IDS" > "${WORK_DIR}/expected-events.txt"

diff \
    "${WORK_DIR}/expected-events.txt" \
    "${WORK_DIR}/delivered-events.txt" \
    || fail "Missing or duplicate event deliveries"

echo "$deliveries" |
    jq -r '.content[].id' |
    sort > "$DELIVERY_IDS"

jq -Rr '
    fromjson? |
    objects |
    .deliveryId // empty
' "$RECEIVER_LOG" |
    sort -u > "${WORK_DIR}/received-deliveries.txt"

diff \
    "$DELIVERY_IDS" \
    "${WORK_DIR}/received-deliveries.txt" \
    || fail "Receiver did not receive every delivery"

echo ""
echo "=== Outage Recovery Test PASSED ==="
echo "Accepted events:       $EVENT_COUNT"
echo "Successful deliveries: $EVENT_COUNT"
echo "Missing deliveries:    0"
echo "Dead deliveries:       0"
echo "Endpoint ID:           $endpoint_id"
