# Standards Layer

This repository starts with a standards and payer-simulation foundation. It prepares the artifacts needed for ingestion, identity, interoperability, and a local payer-side PAS simulator without implementing the product agent yet.

## Required Layer

- FHIR R4
- US Core
- USCDI
- SMART
- Bulk FHIR
- OIDC

The required layer is the ingestion, identity, and interoperability foundation for the CareParse graph. FHIR R4 provides the resource model, US Core constrains US clinical exchange, USCDI defines the expected data classes and elements, SMART and OIDC cover application launch and identity primitives, and Bulk FHIR supports population-scale data access patterns.

## Payer Simulation Layer

- CARIN BB
- PDex
- Formulary
- Plan-Net
- CRD
- DTR
- PAS

The payer simulation layer starts with Da Vinci PAS for prior authorization status inquiry. CRD and DTR are pulled as workflow context, but the first simulator only exposes payer-side PAS status behavior. CARIN BB, PDex, Formulary, and Plan-Net remain future context.

## Artifact Policy

Use `scripts/fetch-specs.sh` to retrieve machine-readable packages and exports into `generated/`. Do not mirror implementation guide websites or copy rendered navigation pages into this repository.
