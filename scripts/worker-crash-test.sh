#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

JAR="${ROOT_DIR}/build/libs/webhook-relay-0.0.1-SNAPSHOT.jar"

WORKER_A_PORT=8081
WORKER_B_PORT=8082
RECEIVER_PORT=8092

WORKER_A_URL="http://127.0.0.1:${WORKER_A_PORT}"
WORKER_B_URL="http://127.0.0.1:${WORKER_B_PORT}"
RECEIVER_URL="http://127.0.0.1:${RECEIVER_PORT}"

EVENT_TYPE="crash.test.$(uuidgen)"

WORK_DIR=$(mktemp -d)

WORKER_A_LOG="${WORK_DIR}/worker-a.log"
WORKER_B_LOG="${WORK_DIR}/worker-b.log"
RECEIVER_LOG="${WORK_DIR}/receiver.log"

WORKER_A_PID=""
WORKER_B_PID=""
RECEIVER_PID=""

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

cleanup() {
    for pid in "$WORKER_A_PID" "$WORKER_B_PID" "$RECEIVER_PID"; do
        if [[ -n "$pid" ]]; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done

    echo "Evidence directory: ${WORK_DIR}"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for cmd in java curl jq python3 uuidgen lsof docker; do
    command -v "$cmd" >/dev/null \
        || fail "$cmd is required"
done

[[ -f "$JAR" ]] || fail "Executable JAR not found"
[[ -f "${ROOT_DIR}/.env" ]] || fail ".env not found"

cd "$ROOT_DIR"

set -a
source .env
set +a

for port in "$WORKER_A_PORT" "$WORKER_B_PORT" "$RECEIVER_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        fail "Port $port is already in use"
    fi
done

db_query() {
    docker compose exec -T postgres \
        psql -X -v ON_ERROR_STOP=1 \
        -U "$POSTGRES_USER" \
        -d "$POSTGRES_DB" \
        -At -F '|' \
        -c "$1"
}

wait_for_health() {
    local url="$1"
    local pid="$2"

    for ((i=0; i<120; i++)); do
        kill -0 "$pid" 2>/dev/null \
            || fail "Process $pid exited unexpectedly"

        if curl -fsS --max-time 1 "$url" >/dev/null 2>&1; then
            return 0
        fi

        sleep 0.5
    done

    fail "Health check timed out: $url"
}

get_delivery_state() {
    db_query "
        SELECT status,
               attempt_count,
               CASE
                   WHEN locked_until > now() THEN 'ACTIVE'
                   WHEN locked_until IS NULL THEN 'NONE'
                   ELSE 'EXPIRED'
               END
        FROM deliveries
        WHERE id = '${delivery_id}';
    "
}

get_attempt_count() {
    db_query "
        SELECT count(*)
        FROM delivery_attempts
        WHERE delivery_id = '${delivery_id}';
    "
}

echo "=== Worker Crash Recovery Test ==="

echo "[1/9] Checking database..."

db_query "SELECT 1;" >/dev/null

eligible_count=$(db_query "
    SELECT count(*)
    FROM deliveries
    WHERE status IN ('PENDING', 'IN_PROGRESS');
")

[[ "$eligible_count" == "0" ]] \
    || fail "Existing unfinished deliveries detected. Use an idle local database."

echo "[2/9] Starting crash receiver..."

python3 -u "${SCRIPT_DIR}/crash-receiver.py" \
    --port "$RECEIVER_PORT" \
    > "$RECEIVER_LOG" 2>&1 &

RECEIVER_PID=$!

wait_for_health "${RECEIVER_URL}/health" "$RECEIVER_PID"

echo "[3/9] Starting Worker A..."

APP_PORT="$WORKER_A_PORT" \
    java -jar "$JAR" \
    --spring.profiles.active=local \
    --app.worker.enabled=true \
    --app.worker.id=crash-worker-a \
    > "$WORKER_A_LOG" 2>&1 &

WORKER_A_PID=$!

wait_for_health \
    "${WORKER_A_URL}/actuator/health" \
    "$WORKER_A_PID"

echo "Worker A PID: $WORKER_A_PID"

echo "[4/9] Registering endpoint..."

endpoint_response=$(curl -fsS --max-time 5 \
    -X POST "${WORKER_A_URL}/endpoints" \
    -H "Content-Type: application/json" \
    -d "$(jq -n \
        --arg url "${RECEIVER_URL}/webhook" \
        --arg type "$EVENT_TYPE" \
        '{url: $url, eventTypes: [$type]}')")

endpoint_id=$(echo "$endpoint_response" | jq -er '.id')

echo "Endpoint ID: $endpoint_id"

echo "[5/9] Publishing event and triggering crash..."

# Publish asynchronously so the script can immediately
# monitor the receiver while the event request is running.

curl -fsS --max-time 5 \
    -X POST "${WORKER_A_URL}/events" \
    -H "Content-Type: application/json" \
    -d "$(jq -n \
        --arg type "$EVENT_TYPE" \
        '{type: $type, payload: {
            orderId: 123,
            source: "worker-crash-test"
        }}')" \
    > "${WORK_DIR}/event-response.json" &

PUBLISH_PID=$!

crash_triggered=false

for ((i=0; i<400; i++)); do
    if grep -q '"event": "webhook_received"' "$RECEIVER_LOG"; then
        echo "First HTTP request detected"

        kill -9 "$WORKER_A_PID"

        wait "$WORKER_A_PID" 2>/dev/null || true
        WORKER_A_PID=""

        crash_triggered=true
        break
    fi

    sleep 0.025
done

wait "$PUBLISH_PID" 2>/dev/null || true

[[ "$crash_triggered" == "true" ]] \
    || fail "Crash trigger timed out"

delivery_id=$(jq -Rr '
    fromjson? |
    objects |
    select(
        .event == "webhook_received"
        and .requestCount == 1
    ) |
    .deliveryId
' "$RECEIVER_LOG" | head -n 1)

[[ "$delivery_id" =~ ^[0-9a-fA-F-]{36}$ ]] \
    || fail "Invalid delivery ID"

echo "Delivery ID: $delivery_id"

echo "[6/9] Verifying persisted state after crash..."

state=$(get_delivery_state)

echo "$state" | tee "${WORK_DIR}/after-crash.txt"

[[ "$state" == "IN_PROGRESS|1|ACTIVE" ]] \
    || fail "Unexpected state after crash: $state"

[[ "$(get_attempt_count)" == "0" ]] \
    || fail "Crashed worker persisted an attempt result"

echo "Worker A terminated before result persistence"

echo "[7/9] Starting Worker B..."

APP_PORT="$WORKER_B_PORT" \
    java -jar "$JAR" \
    --spring.profiles.active=local \
    --app.worker.enabled=true \
    --app.worker.id=crash-worker-b \
    > "$WORKER_B_LOG" 2>&1 &

WORKER_B_PID=$!

wait_for_health \
    "${WORKER_B_URL}/actuator/health" \
    "$WORKER_B_PID"

echo "Worker B PID: $WORKER_B_PID"

state=$(get_delivery_state)

[[ "$state" == "IN_PROGRESS|1|ACTIVE" ]] \
    || fail "Could not observe an active lease with Worker B running: $state"

sleep 1

state=$(get_delivery_state)

echo "$state" | tee "${WORK_DIR}/before-expiry.txt"

[[ "$state" == "IN_PROGRESS|1|ACTIVE" ]] \
    || fail "Active lease protection could not be verified: $state"

echo "Worker B did not reclaim the active lease"

echo "[8/9] Waiting for natural lease expiration and recovery..."

recovered=false

for ((i=0; i<60; i++)); do
    state=$(get_delivery_state)

    echo "Delivery state: $state"

    case "$state" in
        "SUCCEEDED|2|NONE")
            recovered=true
            break
            ;;
        DEAD*)
            fail "Delivery became DEAD"
            ;;
    esac

    sleep 1
done

[[ "$recovered" == "true" ]] \
    || fail "Worker recovery timed out"

echo "$state" > "${WORK_DIR}/after-recovery.txt"

echo "[9/9] Verifying attempt history and duplicate reception..."

attempt_history=$(db_query "
    SELECT attempt_no, status_code
    FROM delivery_attempts
    WHERE delivery_id = '${delivery_id}'
    ORDER BY attempt_no;
")

echo "$attempt_history" |
    tee "${WORK_DIR}/attempt-history.txt"

[[ "$attempt_history" == "2|200" ]] \
    || fail "Unexpected attempt history: $attempt_history"

request_counts=$(jq -Rr \
    --arg id "$delivery_id" '
    fromjson? |
    objects |
    select(
        .event == "webhook_received"
        and .deliveryId == $id
    ) |
    .requestCount
' "$RECEIVER_LOG")

[[ "$request_counts" == $'1\n2' ]] \
    || fail "Expected exactly two requests with the same delivery ID"

grep -F "event=delivery_processed workerId=crash-worker-b deliveryId=${delivery_id} attempt=2 httpStatus=200" \
    "$WORKER_B_LOG" \
    > "${WORK_DIR}/recovery-worker.txt" \
    || fail "Worker B recovery log not found"

echo ""
echo "=== Worker Crash Recovery Test PASSED ==="
echo "Delivery ID:             $delivery_id"
echo "Final status:            SUCCEEDED"
echo "Claimed attempts:        2"
echo "Persisted attempts:      1"
echo "Receiver requests:       2"
echo "Premature reclaim:       Not observed"
echo "Recovery worker:         crash-worker-b"
echo "Natural lease expiry:    Verified"