# Why Not Vendor Full IG Websites

CareParse PayerLab should not commit complete implementation guide HTML exports, full websites, generated navigation trees, screenshots, or rendered documentation bundles.

Machine consumers need the computable artifacts:

- `StructureDefinition`
- `ValueSet`
- `CodeSystem`
- `OperationDefinition`
- `CapabilityStatement`
- examples
- schemas

Rendered HTML is useful for human reading, but it is noisy as repository source material. It is large, changes frequently for presentation-only reasons, and obscures the artifact contract that ingestion and validation tools actually use.

The repository should commit manifests, lock files, fetch scripts, verification scripts, checksums when freezing a fetched artifact set, selected examples, and architecture documentation. The artifact cache under `generated/` should be reproducible from `packages/lock.yaml`.
