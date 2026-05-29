# Conformance Targets

CareParse PayerLab now treats external conformance tests as explicit implementation targets.

The active target is `inferno-smart-backend`, recorded in `packages/conformance-targets.json`. This target is the SMART App Launch STU2 discovery and Backend Services Authorization path used by the Inferno SMART App Launch Test Kit.

## SMART Backend Services

Public endpoints:

```text
GET  /fhir/.well-known/smart-configuration
GET  /auth/.well-known/openid-configuration
GET  /auth/jwks.json
POST /auth/token
GET  /fhir/metadata
```

Registered backend client:

```text
client_id=payerlab-provider-backend
token_endpoint_auth_method=private_key_jwt
grant_type=client_credentials
```

Non-production fixture keys live in `fixtures/auth/`. They are deterministic test material only and must not be used outside the simulator.

Run the backend-services auth smoke test against a running simulator:

```bash
PAYERLAB_BASE_URL=http://localhost:8080 ./scripts/smoke-smart-backend-auth.sh >/dev/null
```

Run the authenticated workflow smoke test:

```bash
PAYERLAB_BASE_URL=http://localhost:8080 ./scripts/smoke-workflow.sh
```

## Planned Inferno Targets

- `inferno-pas-server-2.0.1`
- `inferno-dtr-payer-server-2.0.1`
- `inferno-crd-server-2.0.1`

The repo still tracks newer CRD/DTR/PAS packages for implementation exploration. The Inferno target registry keeps the external test-kit version explicit so test harness work can prioritize what Inferno currently validates.
