#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EVENT_TYPE="demo.order.created"
RECEIVER_URL="${BASE_URL}/mock-receiver"

command -v curl >/dev/null || {
  echo "ERROR: curl is required"
  exit 1
}

command -v jq >/dev/null || {
  echo "ERROR: jq is required"
  exit 1
}

echo "=== Webhook Relay Demo ==="

# 1. Create an endpoint.
echo ""
echo "[1/4] Creating endpoint..."

endpoint_response=$(curl -fsS \
  -X POST "${BASE_URL}/endpoints" \
  -H "Content-Type: application/json" \
  -d "$(jq -n \
    --arg url "$RECEIVER_URL" \
    --arg type "$EVENT_TYPE" \
    '{url: $url, eventTypes: [$type]}')")

endpoint_id=$(echo "$endpoint_response" | jq -r '.id')

if [[ -z "$endpoint_id" || "$endpoint_id" == "null" ]]; then
  echo "ERROR: Endpoint ID not returned"
  exit 1
fi

echo "Endpoint ID: $endpoint_id"

# 2. Publish an event.
echo ""
echo "[2/4] Publishing event..."

event_response=$(curl -fsS \
  -X POST "${BASE_URL}/events" \
  -H "Content-Type: application/json" \
  -d "$(jq -n \
    --arg type "$EVENT_TYPE" \
    '{type: $type, payload: {orderId: 123, source: "demo"}}')")

event_id=$(echo "$event_response" | jq -r '.eventId')

if [[ -z "$event_id" || "$event_id" == "null" ]]; then
  echo "ERROR: Event ID not returned"
  exit 1
fi

echo "Event ID: $event_id"

# 3. Wait for worker processing.
echo ""
echo "[3/4] Waiting for delivery..."

delivery_id=""
delivery_status=""

for i in {1..20}; do
  deliveries=$(curl -fsS \
    "${BASE_URL}/deliveries?endpointId=${endpoint_id}&size=10")

  delivery=$(echo "$deliveries" | jq -c \
    --arg eventId "$event_id" \
    '.content[] | select(.eventId == $eventId)' | head -n 1)

  if [[ -n "$delivery" ]]; then
    delivery_id=$(echo "$delivery" | jq -r '.id')
    delivery_status=$(echo "$delivery" | jq -r '.status')

    if [[ "$delivery_status" == "SUCCEEDED" || \
          "$delivery_status" == "DEAD" ]]; then
      break
    fi
  fi

  sleep 1
done

if [[ -z "$delivery_id" ]]; then
  echo "ERROR: No delivery found for event $event_id"
  exit 1
fi

echo "Delivery ID: $delivery_id"
echo "Status: $delivery_status"

# 4. Retrieve delivery details.
echo ""
echo "[4/4] Fetching delivery details..."

curl -fsS "${BASE_URL}/deliveries/${delivery_id}" | jq .

if [[ "$delivery_status" != "SUCCEEDED" ]]; then
  echo "ERROR: Delivery did not succeed (status: $delivery_status)"
  exit 1
fi

echo ""
echo "=== Demo completed successfully ==="
