# CareParse PayerLab

CareParse PayerLab is a prior authorization and ASC case-readiness sandbox for Medicare and Medicare Advantage workflows.

This repository is currently scoped to the standards documentation layer only. It records the required interoperability standards, provides a reproducible fetch pipeline for machine-readable artifacts, and leaves future payer implementation guides disabled until a later phase.

## Current Scope

- Maintain a standards manifest in `packages/lock.yaml`.
- Fetch required standards artifacts into `generated/`.
- Verify artifact versions, checksums, extraction, and expected resource files.
- Document the standards layer and why the repository does not vendor complete implementation guide websites.

## Non-Goals

This phase does not implement payer APIs, agents, user interfaces, knowledge graph logic, prior authorization engines, ASC workflows, examples, Medicare rules, denial scoring, appeals, or benchmarks.

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
- `docs/why-not-vendor-igs.md`
