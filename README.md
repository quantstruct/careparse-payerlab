# CareParse PayerLab

CareParse PayerLab is a payer-side prior authorization sandbox for Medicare and Medicare Advantage workflows.

This repository records the required interoperability standards, provides a reproducible fetch pipeline for machine-readable artifacts, and hosts a local payer-side PAS simulator that product and AI-agent workflows can test against before production payer integrations.

## Current Scope

- Maintain a standards manifest in `packages/lock.yaml`.
- Fetch required standards and payer implementation-guide artifacts into `generated/`.
- Verify artifact versions, checksums, extraction, and expected resource files.
- Simulate payer-side PAS prior authorization status inquiry with deterministic fixtures.
- Document the standards layer and why the repository does not vendor complete implementation guide websites.

## Non-Goals

This phase does not implement the product agent, user interfaces, knowledge graph logic, full prior authorization decision engines, ASC workflows, Medicare policy rules, denial scoring, appeals, benchmarks, X12 conversion, or live payer integrations.

## Usage

Fetch standards artifacts:

```bash
./scripts/fetch-specs.sh
```

Verify the local standards cache:

```bash
./scripts/verify-specs.sh
```

The generated artifacts are local build outputs. The committed source of truth is the manifest, fetch script, verification script, and documentation.

## Docs

- `docs/standards.md`
- `docs/payer-simulator.md`
- `docs/why-not-vendor-igs.md`
