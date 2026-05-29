#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${PAYERLAB_BASE_URL:-http://localhost:8080}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
ACCESS_TOKEN="$(./scripts/smoke-smart-backend-auth.sh)"
AUTH_HEADER="Authorization: Bearer $ACCESS_TOKEN"

curl -fsS \
  -H "$AUTH_HEADER" \
  "$BASE_URL/cds-services" \
  > "$TMP_DIR/01-crd-discovery.json"

curl -fsS \
  -H "$AUTH_HEADER" \
  -H 'Content-Type: application/json' \
  --data @fixtures/payer/requests/workflow-crd-order-sign.json \
  "$BASE_URL/cds-services/payerlab-crd-prior-auth" \
  > "$TMP_DIR/02-crd-card.json"

curl -fsS \
  -H "$AUTH_HEADER" \
  -H 'Accept: application/fhir+json' \
  "$BASE_URL/fhir/Questionnaire/payerlab-dental-orthodontics" \
  > "$TMP_DIR/03-dtr-questionnaire.json"

curl -fsS \
  -H "$AUTH_HEADER" \
  -H 'Content-Type: application/fhir+json' \
  --data @fixtures/payer/requests/workflow-dtr-questionnaire-response.json \
  "$BASE_URL/fhir/QuestionnaireResponse/\$submit" \
  > "$TMP_DIR/04-dtr-submit.json"

curl -fsS \
  -H "$AUTH_HEADER" \
  -H 'Content-Type: application/fhir+json' \
  --data @fixtures/payer/requests/workflow-pas-submit.bundle.json \
  "$BASE_URL/fhir/Claim/\$submit" \
  > "$TMP_DIR/05-pas-submit.json"

grep -q 'DENTAL-PA-WORKFLOW-2026-0001' "$TMP_DIR/05-pas-submit.json"

curl -fsS \
  -H "$AUTH_HEADER" \
  -H 'Content-Type: application/fhir+json' \
  --data @fixtures/payer/requests/workflow-pas-inquiry.bundle.json \
  "$BASE_URL/fhir/Claim/\$inquire" \
  > "$TMP_DIR/06-pas-inquiry.json"

grep -q '"code": "approved"' "$TMP_DIR/06-pas-inquiry.json"
grep -q 'DENTAL-PA-WORKFLOW-2026-0001' "$TMP_DIR/06-pas-inquiry.json"

echo "CRD -> DTR -> PAS workflow completed against $BASE_URL"
