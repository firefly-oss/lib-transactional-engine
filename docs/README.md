# Documentation Overview

Welcome to the Transactional Engine documentation. This folder collects practical guides, deep dives, and quick references to help you understand, integrate, and operate the library effectively.

If you are new, start with the Tutorial for a hands‑on walkthrough, then skim the Architecture and Deep Dive to internalize concepts. Keep the Reference Card open while you code. If you’re evaluating orchestration styles, read the SAGA vs TCC note.

## Highlights (from the codebase)

These are key capabilities implemented in the current code and covered throughout the docs:
- External saga steps: declare steps outside the orchestrator with `@ExternalSagaStep` (see Reference Card “External saga steps” and Deep Dive components section).
- External compensations override in‑class ones: `@CompensationSagaStep` takes precedence when both are present.
- Per‑compensation resilience overrides: tune `compensationRetry`, `compensationBackoffMs`, `compensationTimeoutMs`, and `compensationCritical` per step.
- Per‑item expansion: expand one logical step into N clones at execution time with `ExpandEach` (automatic compensation wiring per clone).
- Results and reporting: `SagaResult` includes compensation results/errors per step; `SagaReport` offers convenient accessors.
- Graph generation: generate a Graphviz diagram of discovered sagas using the built‑in CLI (see root README “Graph generation”).

## Contents

- [TUTORIAL.md](TUTORIAL.md)
  - A step‑by‑step walkthrough introducing core concepts, declaring and executing a Saga, parameter injection, HTTP propagation, and compensation. Includes diagrams.

- [ARCHITECTURE.md](ARCHITECTURE.md)
  - How it works under the hood: registry, execution model, DAG validation, compensation strategies, and observability. With diagrams and rationale.
  - Don’t miss: [Bean topology — singleton vs multiple SagaEngine beans](ARCHITECTURE.md#bean-topology-singleton-vs-multiple-sagaengine-beans) with practical guidance and Spring examples.

- [DEEP_DIVE_INTO_THE_ENGINE.md](DEEP_DIVE_INTO_THE_ENGINE.md)
  - An in‑depth tour of internals and advanced usage: StepInvoker, SagaCompensator, layering/concurrency, retry/backoff/timeout with jitter, and failure/rollback flows.

- [SAGA-vs-TCC.md](SAGA-vs-TCC.md)
  - When to choose Saga vs TCC, and trade‑offs for each.

- [REFERENCE_CARD.md](REFERENCE_CARD.md)
  - An at‑a‑glance cheat sheet for annotations, core types, configuration, common APIs, and quick snippets. Includes sections on results/reporting and HTTP helpers.

- [PROGRAMMATIC_QUICK_GUIDE.md](PROGRAMMATIC_QUICK_GUIDE.md)
  - Build sagas programmatically (without annotations): handlers/compensations, dynamic graphs, execution, resilience, patterns, and tests.

## Where to start

1. New to the library? Read the [Tutorial](TUTORIAL.md).
2. Need the big picture? Skim [Architecture](ARCHITECTURE.md) and the [Deep Dive](DEEP_DIVE_INTO_THE_ENGINE.md).
3. Unsure which pattern to use? Check [SAGA vs TCC](SAGA-vs-TCC.md).
4. Coding and need quick reminders? Open the [Reference Card](REFERENCE_CARD.md).
5. Building dynamic workflows? Use the [Programmatic Quick Guide](PROGRAMMATIC_QUICK_GUIDE.md).

## Contributing and style

- Keep documents concise and task‑oriented; link to code where helpful.
- Use relative links between markdown files (e.g., `TUTORIAL.md`).
- Prefer simple Markdown compatible with GitHub rendering.
- When adding new docs, update this README with a short description and link.

## Related resources

- Project overview, quick start, compensation policies, and graph generator: [../README.md](../README.md#graph-generation-sagas-dag-via-graphviz)
- Javadoc API (if generated locally): `target/apidocs/index.html`

---

Last updated: 2025-08-21
