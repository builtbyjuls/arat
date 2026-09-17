# Arat? Architecture

Status: Target design

This document describes the intended architecture. It is not evidence that every component has been implemented or load tested. Test and performance results must be added only after they are reproduced.

## 1. Purpose

Arat? is a group-first planning and provider-matching platform for casual outings. A group agrees on its schedule, headcount, budget, location, and requirements. The organizer can then publish an anonymized request to suitable providers. Providers submit sealed offers, group members vote, the organizer selects an offer, and the provider confirms or declines the match.

The request-first path is the Release 1 scope. A later listing extension adds
a second discovery path:

1. Request-first: a group publishes requirements and providers respond.
2. Listing-first: a group finds a provider listing and requests an offer.

Both paths converge on the same `Offer -> Match` workflow. This avoids two booking models with different correctness rules.

The system does not process real payments and does not claim real-time provider
inventory. A planned billing extension is simulated rather than connected to a
payment processor. A submitted offer expresses terms and apparent
availability; it becomes an agreement only after the provider confirms the
selected match.

## 2. Architecture drivers

The architecture is shaped by these requirements:

- Keep private group information separate from provider-visible requirements.
- Preserve exactly which request terms a provider answered.
- Allow many members to update preferences and votes concurrently.
- Guarantee that a single-destination plan has at most one active match.
- Resolve selection, withdrawal, expiration, confirmation, and timeout races deterministically.
- Accept retries from browsers, mobile clients, queues, and simulated billing callbacks.
- Deliver notifications asynchronously without losing committed domain changes.
- Run the complete development and test environment locally without an AWS account.
- Keep the implementation small enough for one engineer to understand and operate.

## 3. System context

```mermaid
flowchart LR
    Member[Group member]
    Organizer[Group organizer]
    ProviderUser[Provider staff]
    Admin[Platform operator]

    Arat[Arat? application]
    Postgres[(PostgreSQL)]
    Queue[Floci SQS]
    Mailpit[Mailpit]

    Member -->|preferences and votes| Arat
    Organizer -->|publish and select| Arat
    ProviderUser -->|offers and confirmation; listings later| Arat
    Admin -->|verification; billing simulation later| Arat

    Arat --> Postgres
    Arat --> Queue
    Queue --> Arat
    Arat --> Mailpit
```

Arat? is the authority for groups, plans, requests, offers, matches, and simulated provider subscriptions. PostgreSQL is the durable source of truth. SQS carries asynchronous work; it is not the authority for business state.

## 4. Architectural style

Arat? starts as a modular monolith built with Java 21 and Spring Boot. It is one deployable application with one PostgreSQL database, but code and schema ownership are divided into explicit business modules.

This is a deliberate choice:

- The consistency-sensitive workflows are easier to implement and explain with local database transactions.
- The initial scope and expected deployment size do not justify distributed transactions or independently operated services.
- Module boundaries still make dependencies visible and allow later extraction based on measured need.

See [ADR-0001](adr/0001-modular-monolith.md).

### 4.1 Layering inside each module

Each module follows the same small internal structure:

```text
module-name/
  api/             Public commands, queries, events, and DTOs
  application/     Use cases and transaction boundaries
  domain/          Domain rules, value objects, and state transitions
  infrastructure/  SQL, queue adapters, and external adapters
```

Controllers translate HTTP input and authentication context into application commands. They do not contain business rules. Persistence rows are not returned directly from the API.

An interface is introduced only at a real boundary, such as time, identity, queue delivery, or email delivery. The identity API is an interface even with one initial adapter because business modules must depend on an authenticated actor contract, not on the development or future production authentication mechanism. Internal classes do not receive an interface solely for test mocking.

### 4.2 Persistence approach

- Flyway owns reproducible schema migrations.
- PostgreSQL constraints enforce invariants that must survive concurrent application instances.
- `JdbcClient` and explicit SQL implement critical state transitions and contention-sensitive queries.
- Straightforward CRUD may use Spring Data JDBC when it does not obscure locking, predicates, or generated SQL important to correctness.
- PostgreSQL behavior is tested with Testcontainers. H2 is not used as a substitute.

See [ADR-0002](adr/0002-postgresql-coordination.md).

## 5. Modules and ownership

| Module | Owns | Does not own |
|---|---|---|
| Identity | User accounts, authentication identity, platform-level roles, sessions | Group membership or provider employment |
| Groups | Groups, memberships, invitations, organizer role | Plans, offers, provider profiles |
| Planning | Plans, requirement drafts, candidate dates, attendance, preferences, published request versions | Provider offers, votes, or matches |
| Marketplace | Request recipients, offers, offer votes, selection attempts, matches | Provider profile content or notification delivery |
| Providers | Provider organizations, staff membership, listings, service areas, capabilities | Match state or provider subscription billing |
| Matching | Eligibility rules for selecting request recipients | Any durable business or delivery state |
| Billing (extension) | Simulated subscriptions, entitlements, usage counters, and billing event inbox | Invoices, real payment instruments, or settlement |
| Messaging | Transactional outbox, SQS envelopes, relay claims, and consumer inbox deduplication | Social chat or domain decisions |
| Notification | Notification preferences, delivery jobs, email rendering, delivery attempts | Domain decisions that cause notifications |

### 5.1 Dependency direction

Modules communicate through explicit module APIs and domain events. They must not query another module's tables directly.

```mermaid
flowchart TD
    Groups --> Identity
    Providers --> Identity
    Planning --> Groups
    Marketplace --> Groups
    Marketplace --> Planning
    Marketplace --> Providers
    Marketplace --> Matching
    Marketplace --> Billing
    Matching --> Providers
    Groups --> Messaging
    Planning --> Messaging
    Marketplace --> Messaging
    Billing --> Messaging
    Notification --> Messaging
```

The arrows show allowed use of a module's public API, not table ownership. Notification consumes facts emitted by other modules. Domain modules do not call email or SQS to complete a transaction.

Potential dependency cycles are avoided as follows:

- Marketplace stores referenced identity values and validated snapshots, not foreign module objects.
- Marketplace passes the immutable request criteria to Matching, which applies
  eligibility rules against provider data exposed by Providers. Marketplace
  records the resulting bounded recipient set during publication. Matching
  owns no durable state.
- Notification consumes events and never changes marketplace state.
- Billing exposes entitlements to Marketplace but does not decide which provider matches a request.

Module boundary tests will fail the build if code imports another module's internal packages.

### 5.2 Cross-module transaction owners

Module ownership does not mean each call opens a separate transaction. Public
module APIs are in-process calls that may join the transaction opened by the
application use case that coordinates them.

- Marketplace owns the `PublishRequest` use case. In one transaction it asks
  Groups to lock and verify organizer authority, asks Planning to lock and
  version the plan snapshot, uses Matching eligibility rules with provider
  data exposed by Providers, records the bounded recipient set, and asks
  Messaging to append notification work.
- Marketplace also owns offer submission, selection, confirmation, decline,
  vote, and timeout use cases. It calls Groups, Planning, and Providers for
  guarded authority and state changes in the documented lock order, Billing
  for a quota claim when required, and Messaging for outbox writes.
- Each called module changes only its own tables. The transaction owner may
  coordinate public module APIs but may not query another module's tables
  directly.

This preserves one PostgreSQL commit for the invariant without hiding the
dependency behind a distributed workflow.

## 6. Core domain model

```mermaid
erDiagram
    GROUP ||--o{ GROUP_MEMBER : contains
    GROUP ||--o{ PLAN : creates
    PLAN ||--o{ PLAN_PREFERENCE : collects
    PLAN ||--o{ PUBLISHED_REQUEST : versions
    PUBLISHED_REQUEST ||--o{ OFFER : receives
    PROVIDER ||--o{ LISTING : publishes
    PROVIDER ||--o{ OFFER : submits
    LISTING o|--o{ OFFER : originates
    OFFER ||--o{ OFFER_VOTE : receives
    PLAN ||--o{ MATCH : attempts
    OFFER ||--o| MATCH : selected_for
    PROVIDER ||--|| PROVIDER_SUBSCRIPTION : has
```

Important modeling choices:

- A `Plan` is the private collaboration space for one outing.
- A `PublishedRequest` is an immutable provider-visible snapshot. Material changes create a new version.
- An `Offer` is a sealed response to exactly one request version. It is not silently rewritten when a plan changes.
- Votes advise the organizer. They do not automatically select an offer.
- Selecting an offer creates a `Match` in `AWAITING_PROVIDER_CONFIRMATION`.
- A match becomes `CONFIRMED` only when an authorized provider staff member confirms it before the deadline.
- A plan may have only one active match, while declined, timed-out, and cancelled attempts remain available for audit. At most one match may ever reach `CONFIRMED` for a plan.

See [ADR-0003](adr/0003-versioned-requests-and-provider-confirmation.md).

## 7. Critical workflows

### 7.1 Request-first workflow

```mermaid
sequenceDiagram
    participant O as Organizer
    participant A as Arat?
    participant DB as PostgreSQL
    participant Q as Notification SQS
    participant P as Provider

    O->>A: Publish plan requirements
    A->>DB: Insert request, recipient records, and notification outbox rows
    DB-->>A: Commit
    A-->>O: Published request
    A->>Q: Relay provider notifications after commit
    Q->>A: Deliver notification work
    A-->>P: Notify matched provider
    P->>A: Submit sealed offer
    A->>DB: Insert offer and outbox event
    A-->>P: Offer accepted
```

Provider matching is bounded database-backed application work during publication. A provider that qualifies through several capabilities still receives one recipient record because `(published_request_id, provider_id)` is unique.

The committed recipient set is the fixed audience for that request version.
Recovery may retry delivery from its existing recipient and outbox rows, but it
must not rerun eligibility rules to add providers. Reaching a broader audience
requires the organizer to publish a new request version.

Each recipient records the provider eligibility version used during matching.
Actionable-brief access, notification delivery, and offer submission recheck
the current version and active verification state. This makes a concurrent
suspension take effect without locking every candidate provider inside the
publication transaction. A stale notification job is recorded as skipped and
sends no email. The provider can still read its own historical offers and
matches through provider membership.

### 7.2 Listing-first workflow (listing extension)

The group adds a provider listing to its plan and asks that provider for a custom offer. Arat? still creates a published request version, but its initial audience contains the chosen provider. The provider submits the same `Offer` used in the request-first path.

This design keeps voting, selection, confirmation, auditing, and failure handling identical in both paths.

### 7.3 Offer selection and confirmation

```mermaid
sequenceDiagram
    participant O as Organizer
    participant A as Arat?
    participant DB as PostgreSQL
    participant P as Provider

    O->>A: Select offer with idempotency key
    A->>DB: Validate current request and active offer
    A->>DB: Insert pending match and outbox event
    DB-->>A: Commit one active match
    A-->>O: Awaiting provider confirmation
    A-->>P: Confirmation requested asynchronously
    P->>A: Confirm before deadline
    A->>DB: Conditional pending to confirmed transition
    DB-->>A: Commit
    A-->>O: Match confirmed asynchronously
```

The unique active-match constraint, not an in-memory lock, decides simultaneous selection attempts. Provider confirmation and timeout use database time and conditional state transitions.

## 8. Transaction boundaries

Transactions are narrow and contain only database work. No HTTP, SQS, email, geocoding, or other network request occurs inside a domain transaction.

| Use case | Transaction contents | After commit |
|---|---|---|
| Publish request | Lock group then plan, verify current organizer and plan ETag, capture immutable snapshot, supersede previous version, calculate a bounded recipient set, insert recipient and notification outbox rows | Relay provider notifications |
| Submit offer | Claim idempotency key, validate provider and current request version, insert sealed offer, append outbox event | Notify group members |
| Cast vote | Lock group, plan, request, and offer; verify current member; conditionally create or version the member's vote | Optional notification aggregation |
| Select offer | Claim idempotency key, lock group, provider, then plan; verify current organizer and plan ETag; validate offer and deadline; create one pending match; update plan state; append outbox event | Ask provider to confirm |
| Confirm match | Lock provider, plan, request, affected offers, then match; verify authority, eligibility, and database deadline; transition to confirmed; close the request; mark other offers not selected; append outbox event | Notify group |
| Expire confirmation | Lock plan, request, affected offers, then match; transition overdue pending match to timed out; reopen the request or close it and mark remaining submitted offers not selected; update the plan; append outbox event | Notify both parties |
| Process simulated billing event | Deduplicate event, transition subscription, append audit and outbox events | Notify provider staff |

Detailed race outcomes and lock order are specified in [Consistency and Concurrency](consistency-and-concurrency.md).

## 9. Asynchronous processing

Arat? uses the transactional outbox pattern for notifications:

1. A domain transaction changes business state and inserts an outbox row atomically.
2. A relay claims committed rows in bounded batches using `FOR UPDATE SKIP LOCKED`.
3. The relay sends messages to SQS.
4. Consumers record the event identifier in an inbox table in the same transaction as their side effect.
5. Repeated delivery returns success without repeating the side effect.

Delivery is at least once. The design does not claim exactly-once messaging.

Release 1 queues:

- `arat-notifications`: one standard queue for email and in-application notification work.
- `arat-notifications-dlq`: one dead-letter queue for notification messages that exhaust the retry policy.

Provider matching does not use SQS in Release 1. Publication calculates a bounded recipient set and stores it in PostgreSQL. Notification outbox rows for those recipients are relayed through the single notification queue.

Floci provides local SQS-compatible queues. Queue endpoints and credentials are external configuration so the same adapters can target AWS SQS later. Mailpit captures local email and is not presented as an AWS emulator.

## 10. API principles

- JSON over HTTP for the MVP.
- Stable resource identifiers are UUIDs generated by the application.
- Commands that may be retried accept an `Idempotency-Key` header.
- Updates use explicit commands such as `/offers/{id}/withdraw` or `/matches/{id}/confirm`, not generic status mutation.
- Request validation errors use predictable problem details.
- Authorization is checked at the application boundary and again where ownership is material to a state change.
- List endpoints use cursor pagination; unbounded lists are prohibited.
- Timestamps are returned in UTC with explicit offsets. A plan stores its display time zone separately.
- Monetary values use a decimal string plus an ISO currency code at the HTTP
  boundary and integer minor units in PostgreSQL. The MVP uses PHP centavos
  internally.

## 11. Security and privacy boundaries

- Provider-visible requests contain only the published snapshot, never the private planning discussion.
- Employer names, member names, direct contact data, and precise private locations are excluded from provider matching.
- Provider staff can act only for provider organizations where they have active membership.
- Contact details are revealed only after a match reaches the intended state.
- Audit records capture actor, action, target, time, request correlation, and material state change without copying secrets.
- Logs must not contain invitation tokens, session tokens, or private message bodies.
- Invite tokens and password-reset tokens are stored hashed.

Public stranger recruitment, minors, real payment data, and an open social feed are outside the MVP threat model.

## 12. Observability

Spring Boot Actuator and Micrometer provide health and operational metrics. Structured logs include `trace_id`, `request_id`, `actor_id` where safe, `plan_id`, and `provider_id` where relevant.

Initial metrics include:

- HTTP request rate, latency, and error rate by route family
- Database pool utilization and query latency
- Offer submissions, selections, confirmations, declines, and timeouts
- Selection conflict count
- Idempotency replay and key-mismatch counts
- Outbox oldest-row age and unpublished-row count
- Queue depth, consumer latency, retry count, and dead-letter count
- Notification success and permanent-failure counts
- Matching fan-out size and completion latency

Metrics must distinguish product outcomes from infrastructure failures. For example, a provider decline is not a server error.

No performance numbers belong in documentation until a versioned test scenario, environment, command, and raw result are committed.

## 13. Local deployment and future AWS mapping

The complete local environment runs with Docker Compose:

| Concern | Local | Possible future AWS | Migration boundary |
|---|---|---|---|
| Application | Spring Boot container | ECS/Fargate or another container runtime | OCI image and environment configuration |
| Durable state | PostgreSQL container | RDS PostgreSQL | JDBC, Flyway, PostgreSQL-compatible SQL |
| Async queues | Floci SQS | Amazon SQS | AWS SDK endpoint and credentials |
| Email capture/delivery | Mailpit | Amazon SES or another provider | Notification delivery adapter |
| Metrics | Actuator scrape/log output | Managed metrics platform | Micrometer registry configuration |

These mappings are evolution options, not promises to deploy every AWS service. Local development and automated tests require no AWS account.

## 14. Testing architecture

Testing is part of the design:

- Unit tests cover pure policies such as audience matching, offer eligibility, and entitlement rules.
- Module tests verify authorization and state transitions through public application APIs.
- PostgreSQL Testcontainers tests prove constraints, transaction behavior, and database-time boundaries.
- Concurrency tests coordinate real threads with barriers and verify one observable winner.
- Queue integration tests verify duplicate delivery, retry, and inbox behavior against Floci where appropriate.
- End-to-end tests exercise request publication through offer confirmation using the local stack.
- Load tests model broad marketplace traffic separately from a deliberately contested plan.

Tests must assert durable outcomes, not only HTTP status codes. Examples include one active match, one inbox record, one contribution to usage, and an outbox event matching the committed transition.

## 15. Deliberate omissions

The initial architecture does not include:

- Microservices
- Redis or distributed locks
- Kafka
- Kubernetes
- Real payment processing
- Guaranteed provider inventory
- Dynamic pricing or auctions
- AI recommendations
- Public social discovery
- Native mobile applications
- Cross-provider package bookings

Each omission reduces operational and consistency surface while preserving the project's central engineering value.

## 16. Evolution criteria

Architecture changes should respond to evidence:

- Extract a module only when it needs independent deployment, scaling, or data isolation.
- Add a cache only after query measurements identify a read bottleneck and an invalidation strategy is documented.
- Add provider calendar integrations only after the confirmation workflow is stable and a provider supplies an authoritative inventory API.
- Introduce real billing only with a defined merchant model, refund policy, dispute handling, and compliance review.
- Add new activity categories only after matching quality and provider liquidity are measurable in the initial categories.
