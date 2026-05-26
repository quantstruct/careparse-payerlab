# Standards Layer

This repository starts with the standards layer only. It prepares the artifacts needed for ingestion, identity, and interoperability without implementing prior authorization workflows or payer APIs.

## Required Layer

- FHIR R4
- US Core
- USCDI
- SMART
- Bulk FHIR
- OIDC

The required layer is the ingestion, identity, and interoperability foundation for the CareParse graph. FHIR R4 provides the resource model, US Core constrains US clinical exchange, USCDI defines the expected data classes and elements, SMART and OIDC cover application launch and identity primitives, and Bulk FHIR supports population-scale data access patterns.

## Future Payer Layer

- CARIN BB
- PDex
- Formulary
- Plan-Net
- CRD
- DTR
- PAS

The future payer layer is reserved for prior authorization and payer workflow support. These implementation guides are present only as disabled placeholders in `packages/lock.yaml`; this phase does not implement them.

## Artifact Policy

Use `scripts/fetch-specs.sh` to retrieve machine-readable packages and exports into `generated/`. Do not mirror implementation guide websites or copy rendered navigation pages into this repository.
