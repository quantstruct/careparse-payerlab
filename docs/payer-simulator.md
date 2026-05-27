# Payer Simulator

CareParse PayerLab is the payer-side sandbox that the product and AI agent will test against before production payer integrations.

The first simulator is intentionally narrow:

- FHIR R4 payer endpoint.
- Da Vinci PAS status inquiry.
- Deterministic repo fixtures.
- No authentication.
- No CRD, DTR, PAS submission, subscriptions, X12 conversion, or clinical rules engine.

## First API Surface

The first exposed payer endpoint is:

```text
POST /fhir/Claim/$inquire
```

The request is a PAS Inquiry Request Bundle. The response is a PAS Inquiry Response Bundle or a FHIR `OperationOutcome`.

The simulator matches an inquiry using:

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

Run the tests with:

```bash
./mvnw test
```

The implementation exposes only `POST /fhir/Claim/$inquire`. Other FHIR paths return an `OperationOutcome` so the sandbox stays tightly scoped to read-only status inquiry.

## Later Work

Later phases may add:

- `POST /fhir/Claim/$submit`
- PAS subscriptions for pended authorization updates
- CRD requirement discovery
- DTR documentation capture
- SMART/OIDC authorization
- X12 278 integration or mapping
