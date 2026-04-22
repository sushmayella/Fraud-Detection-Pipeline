#!/usr/bin/env bash
# Seeds the local ingestion API with sample transactions.
# Usage: ./scripts/seed-transactions.sh [count]
#   count defaults to 20

set -euo pipefail

COUNT="${1:-20}"
API="${INGESTION_API:-http://localhost:8080}"

if ! curl -sf "$API/actuator/health" >/dev/null 2>&1; then
  echo "Error: ingestion-api is not reachable at $API" >&2
  echo "Start it with: docker-compose up -d ingestion-api" >&2
  exit 1
fi

COUNTRIES=("US" "GB" "JP" "DE" "FR" "CA" "IN" "AU")
MERCHANTS=("coffee-shop-1" "gas-station-7" "online-retailer" "restaurant-42" "grocery-mart")
MCC_CODES=(5411 5541 5732 5812 5999)

for i in $(seq 1 "$COUNT"); do
  country="${COUNTRIES[$((RANDOM % ${#COUNTRIES[@]}))]}"
  merchant="${MERCHANTS[$((RANDOM % ${#MERCHANTS[@]}))]}"
  mcc="${MCC_CODES[$((RANDOM % ${#MCC_CODES[@]}))]}"
  amount=$(awk -v min=5 -v max=500 'BEGIN{srand(); printf "%.2f", min+rand()*(max-min)}')
  user_id="demo-user-$((RANDOM % 50))"
  card="fp_$(openssl rand -hex 6 2>/dev/null || echo "card${i}")"

  response=$(curl -s -w '\n%{http_code}' -X POST "$API/api/v1/transactions" \
    -H "Content-Type: application/json" \
    -d "{
      \"userId\": \"$user_id\",
      \"cardFingerprint\": \"$card\",
      \"amount\": $amount,
      \"currency\": \"USD\",
      \"merchantId\": \"$merchant\",
      \"merchantCategoryCode\": $mcc,
      \"country\": \"$country\"
    }")

  status=$(echo "$response" | tail -n1)
  body=$(echo "$response" | head -n-1)

  if [[ "$status" == "202" ]]; then
    txn_id=$(echo "$body" | grep -oE '"transactionId":"[^"]+"' | cut -d'"' -f4)
    echo "[$i/$COUNT] accepted: $txn_id  (user=$user_id amount=\$$amount $country)"
  else
    echo "[$i/$COUNT] FAILED status=$status body=$body" >&2
  fi
done

echo ""
echo "Done. Browse topics at http://localhost:8090"
