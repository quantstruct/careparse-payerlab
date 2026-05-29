# Payer Simulator

CareParse PayerLab is the payer-side sandbox that the product and AI agent will test against before production payer integrations.

The simulator is intentionally narrow:

- FHIR R4 payer endpoint.
- CRD-style CDS Hooks discovery and order-sign invocation.
- DTR-style payer questionnaire retrieval and QuestionnaireResponse submission.
- Da Vinci PAS-style Claim submission and status inquiry.
- SMART Backend Services bearer-token authorization.
- Deterministic repo fixtures.
- No subscriptions, X12 conversion, user interface, product agent, or clinical rules engine.

## API Surface

CRD-style coverage requirements discovery:

```text
GET /cds-services
POST /cds-services/payerlab-crd-prior-auth
```

DTR-style documentation collection:

```text
GET /fhir/Questionnaire/payerlab-dental-orthodontics
POST /fhir/QuestionnaireResponse/$submit
```

PAS-style submission and inquiry:

```text
POST /fhir/Claim/$submit
POST /fhir/Claim/$inquire
```

`Claim/$submit` accepts a PAS request Bundle and returns a PAS response Bundle with a deterministic prior authorization reference. `Claim/$inquire` accepts a PAS Inquiry Request Bundle and returns a PAS Inquiry Response Bundle or a FHIR `OperationOutcome`.

## Matching

The simulator matches PAS requests using:

- patient member identifier
- payer identifier
- requesting provider NPI
- authorization or reference identifier when supplied

## Fixture States

The payer simulator should include deterministic fixtures for:

- approved
- denied, with a specific denial reason
- pended
- additional information needed
- not found
- malformed or invalid request

Raw FHIR response evidence should remain available so the future product agent can ground answers in source fields instead of free-text inference.

## Local Runtime

Run the simulator with the Maven wrapper:

```bash
PAYERLAB_PORT=8080 ./mvnw exec:java
```

Run the portable Docker stack:

```bash
docker compose up --build
```

Smoke test the running stack:

```bash
curl -sS \
  -H 'Content-Type: application/fhir+json' \
  --data @fixtures/payer/requests/approved-inquiry.bundle.json \
  http://localhost:8080/fhir/Claim/\$inquire
```

Run the dental prior authorization status demo:

```bash
curl -sS \
  -H 'Content-Type: application/fhir+json' \
  --data @fixtures/payer/requests/dental-approved-inquiry.bundle.json \
  http://localhost:8080/fhir/Claim/\$inquire
```

Dental demo fixtures are API-only and return raw FHIR `Bundle` responses. They use FHIR `Claim.type` code `oral` plus local demo dental service codes to avoid depending on licensed dental procedure code content.

Run the CRD -> DTR -> PAS workflow demo against a running simulator:

```bash
PAYERLAB_BASE_URL=http://localhost:8080 ./scripts/smoke-workflow.sh
```

The workflow smoke test obtains a SMART Backend Services access token, performs service discovery, invokes the CRD order-sign service, retrieves the DTR questionnaire, submits a QuestionnaireResponse, submits a PAS Claim Bundle, and inquires on the returned authorization reference.

Run the tests with:

```bash
./mvnw test
```

The implementation exposes only the workflow endpoints above. Other FHIR paths return an `OperationOutcome` so the sandbox stays tightly scoped to payer-side prior authorization simulation.

## Later Work

Later phases may add:

- PAS subscriptions for pended authorization updates
- SMART/OIDC authorization
- X12 278 integration or mapping
