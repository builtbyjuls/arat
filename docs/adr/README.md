# Architecture Decision Records

ADRs record decisions that materially affect correctness, deployment, cost, or
future change. They do not record routine implementation details.

## Index

- [ADR-0001: Start with a modular monolith](0001-modular-monolith.md)
- [ADR-0002: Use PostgreSQL for workflow coordination](0002-postgresql-coordination.md)
- [ADR-0003: Version published requests and require provider confirmation](0003-versioned-requests-and-provider-confirmation.md)
- [ADR-0004: Use an outbox, SQS, and a narrow Floci scope](0004-outbox-sqs-floci.md)

## Status values

- Proposed
- Accepted
- Superseded
- Rejected

An accepted ADR is updated only to clarify context or status. A materially
changed decision creates a new ADR that supersedes the previous one.
