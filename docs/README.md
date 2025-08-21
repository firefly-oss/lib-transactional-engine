# Documentation Overview

Welcome to the Transactional Engine documentation. This folder contains guides and references to help you understand, integrate, and operate the library.

If you are new to the project, start with the Tutorial, then explore the Architecture, and consult the Reference Card as you build. If you are deciding between orchestration patterns, see the SAGA vs TCC note.

## Contents

Note (new): SagaResult now includes compensation results and errors per step, and a new SagaReport class provides convenient access to step and compensation details. See Tutorial section "8.1) Inspecting results and compensations" and the Reference Card section "Results and reporting".

- [TUTORIAL.md](TUTORIAL.md)
  - A step‑by‑step walkthrough that introduces the core concepts, shows how to declare and execute a Saga, and demonstrates parameter injection and compensation.

- [ARCHITECTURE.md](ARCHITECTURE.md)
  - A deeper dive into the engine’s design: registry, execution model, DAG validation, compensation strategies, and observability. Includes diagrams and rationale.
  - Don’t miss: [Bean topology — singleton vs multiple SagaEngine beans](ARCHITECTURE.md#bean-topology-singleton-vs-multiple-sagaengine-beans) with practical guidance and Spring examples.

- [SAGA-vs-TCC.md](SAGA-vs-TCC.md)
  - A short guide comparing the Saga and TCC patterns, with guidance on when to choose each and trade‑offs to consider.

- [REFERENCE_CARD.md](REFERENCE_CARD.md)
  - An at‑a‑glance cheat sheet for annotations, core types, configuration, common APIs, and quick snippets.

- [PROGRAMMATIC_QUICK_GUIDE.md](PROGRAMMATIC_QUICK_GUIDE.md)
  - A step‑by‑step tutorial on building sagas programmatically (without annotations): dynamic DAGs, handlers/compensations, execution, and common patterns.

## Where to start

1. New to the library? Read the [Tutorial](TUTORIAL.md).
2. Need the big picture? Skim [Architecture](ARCHITECTURE.md).
3. Unsure which pattern to use? Check [SAGA vs TCC](SAGA-vs-TCC.md).
4. Coding and need quick reminders? Open the [Reference Card](REFERENCE_CARD.md).

## Contributing and style

- Keep documents concise and task‑oriented. Link out to code where helpful.
- Use relative links between markdown files (e.g., `TUTORIAL.md`).
- Prefer simple Markdown compatible with GitHub rendering.
- When adding new docs, update this README with a short description and link.

## Related resources

- Project overview and usage examples in the repository root: [../README.md](../README.md)
- Javadoc API (if generated locally): `target/apidocs/index.html`

---

Last updated: 2025-08-21
