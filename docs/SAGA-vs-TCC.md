# Why SAGA (compensating transactions) vs TCC (Try-Confirm/Cancel)

This library implements the Saga orchestration pattern. Teams often ask whether TCC would be a better fit. This note explains both, their trade‑offs, and when we recommend each.

## Table of contents
- [Quick definitions](#quick-definitions)
- [Why we picked Saga for this library](#why-we-picked-saga-for-this-library)
- [When to prefer Sagas](#when-to-prefer-sagas)
- [When TCC may be a better fit](#when-tcc-may-be-a-better-fit)
- [Practical guidance](#practical-guidance)

## Quick definitions
- Saga (with compensation)
  - You execute a sequence/graph of local transactions across services.
  - If a later step fails, you execute explicit compensating actions for the already completed steps (refund, release, cancel).
  - Consistency: eventual. Each service commits locally; global consistency is achieved through compensations.
  - Coordination: can be orchestration (this library) or choreography (events). We provide orchestration.

- TCC (Try‑Confirm/Cancel)
  - Every business action has two phases plus a cancel: Try reserves/locks resources tentatively, Confirm commits, Cancel releases.
  - A coordinator calls Try for all participants, then either Confirm all or Cancel all.
  - Consistency: stronger than plain Saga due to explicit reservation/confirm phases. Typically requires participants to implement TCC‑style endpoints and reservation semantics.

## Why we picked Saga for this library
- Simpler integration surface
  - Most existing services already expose business APIs that can be paired with clear compensations (create -> cancel, charge -> refund). They rarely expose full TCC (prepare/confirm/cancel) endpoints.
- Lower coupling to participants
  - Sagas work with today’s APIs. TCC requires all participants to implement reservation and confirmation behavior correctly, which increases coupling and operational complexity.
- Great fit for short‑lived, in‑process orchestration
  - Our engine is in‑memory and single‑JVM by design. For flows consisting of a handful of network calls, compensations provide reliable outcomes without heavy infrastructure.
- Clear operational model
  - Retries, timeouts, idempotency, and explicit compensation order are well understood and easy to observe.

## When to prefer Sagas
- Your services expose natural compensations (refund, release, cancel) even if they don’t support TCC.
- The flow has 2–10 steps with clear dependencies and you can tolerate eventual consistency windows.
- You want to minimize coordination overhead and infrastructure.

## When TCC may be a better fit
- Business demands a narrow inconsistency window and strict resource reservation semantics before confirmation (e.g., high‑value financial trades, scarce inventory with hard holds).
- All participating services can implement Try/Confirm/Cancel endpoints and maintain reservation state reliably.
- You need stronger commit orchestration guarantees than compensations can offer.

## Practical guidance
- Design downstream APIs to be idempotent (natural keys) whether you use Saga or TCC.
- For Sagas, ensure compensations are:
  - Safe to run more than once (idempotent)
  - As fast as practical (to keep rollbacks quick)
- If you already operate a TCC‑capable system, you can still use this orchestrator for surrounding steps (notifications, document generation) while delegating core commit to the TCC domain.

See also: Architecture guide in docs/ARCHITECTURE.md and the README sections on failure, compensation, and policies.
