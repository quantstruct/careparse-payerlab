#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${PAYERLAB_BASE_URL:-http://localhost:8080}"
SCOPES="${PAYERLAB_SCOPES:-system/Coverage.rs system/Questionnaire.rs system/QuestionnaireResponse.c system/Claim.c system/Claim.rs system/ClaimResponse.rs}"
TOKEN_ENDPOINT="$BASE_URL/auth/token"

client_assertion="$(
  ./mvnw -q \
    -Dexec.executable=java \
    -Dexec.args="-cp %classpath com.careparse.payerlab.ClientAssertionGenerator $TOKEN_ENDPOINT" \
    exec:exec \
    | tail -n 1
)"

token_response="$(
  curl -fsS \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=client_credentials' \
    --data-urlencode "scope=$SCOPES" \
    --data-urlencode 'client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer' \
    --data-urlencode "client_assertion=$client_assertion" \
    "$TOKEN_ENDPOINT"
)"

access_token="$(printf '%s' "$token_response" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"

if [[ -z "$access_token" ]]; then
  printf 'Unable to read access_token from token response:\n%s\n' "$token_response" >&2
  exit 1
fi

curl -fsS \
  -H "Authorization: Bearer $access_token" \
  -H 'Accept: application/json' \
  "$BASE_URL/cds-services" \
  >/dev/null

echo "$access_token"
