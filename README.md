# Arat?

Arat? is a planned group-first marketplace for casual outings in the
Philippines.

An existing barkada, work team, family, or club agrees on when it is available,
how many people are joining, where it can go, and what it can spend. The group
can then publish an anonymized requirement to relevant local providers.
Providers return structured, expiring offers. Members compare and vote, the
organizer selects one offer, and the provider confirms the match.

The name comes from Filipino backslang for "tara", or "let's go."

This repository is currently in the design phase. The documents describe the
target system and the evidence required before making implementation,
performance, or scalability claims.

## Why this project exists

Most group outings begin in a chat and stall:

- Members have different schedules and budgets.
- A vote does not prove that someone will join.
- Requirements are copied repeatedly to multiple providers.
- Provider replies are difficult to compare.
- Quotes become stale when the group changes its requirements.
- One organizer carries the planning and follow-up work.

Arat? treats the group plan as the primary product object. Provider listings are
useful, but they are secondary to the group's decision process.

## Product boundary

The initial product boundary covers one dense Philippine metro area and three
casual group-activity categories:

- Badminton and other reservable activity courts
- KTV and private activity rooms
- Group dining with set-menu or package offers

The design has two paths that converge on the same offer and match workflow.
The request-first path is the first release target; listing-first is the next
product extension:

1. A group publishes a requirement and matched providers respond.
2. A group finds a provider listing and asks for a tailored offer.

The target release does not include:

- Public plans that strangers can join
- Real payment processing
- Guaranteed instant booking for every provider
- Chat, social feeds, reviews, or recommendations
- AI planning
- Multi-provider event packages
- Native mobile applications

## Core lifecycle

~~~mermaid
flowchart LR
    Group[Private group] --> Plan[Collaborative plan]
    Plan --> Brief[Versioned provider brief]
    Brief --> Offers[Sealed provider offers]
    Offers --> Vote[Member comparison and vote]
    Vote --> Select[Organizer selection]
    Select --> Confirm[Provider confirmation]
    Confirm --> Match[Confirmed match]
~~~

Member votes are advisory. Only an organizer can publish a provider brief or
select an offer. An accepted offer remains subject to provider confirmation
before a deadline in the initial release.

## Engineering focus

The project is intentionally more than CRUD. It is designed to prove:

1. A published provider brief is immutable and versioned.
2. Every offer references the exact brief version it answers.
3. A stale, expired, withdrawn, or superseded offer cannot be selected.
4. At most one active match exists for a plan.
5. Concurrent attempts to select different offers have one winner.
6. Offer selection, expiration, withdrawal, and provider confirmation races
   have deterministic outcomes.
7. Repeated API commands, billing callbacks, and queue deliveries are harmless.
8. A committed state change and its outbox event are stored atomically.
9. In the later billing extension, provider subscription limits remain correct
   under concurrent offer submission.

These claims must be supported by PostgreSQL constraints, explicit transaction
boundaries, and executable integration and concurrency tests.

## Architecture summary

Arat? starts as a modular monolith. PostgreSQL owns authoritative workflow
state and concurrency coordination. A transactional outbox separates committed
domain changes from asynchronous provider and group notifications.

~~~mermaid
flowchart LR
    Client[Web or API client] --> App[Arat? Spring Boot application]

    subgraph Modules[Application modules]
        Identity[Identity]
        Groups[Groups]
        Planning[Planning]
        Marketplace[Marketplace]
        Providers[Providers]
        Matching[Matching]
        Billing[Billing extension]
        Messaging[Outbox relay]
        Notification[Notification worker]
    end

    App --> Identity
    App --> Groups
    App --> Planning
    App --> Marketplace
    App --> Providers
    App --> Matching
    App --> Billing
    Identity --> DB[(PostgreSQL)]
    Groups --> DB
    Planning --> DB
    Marketplace --> DB
    Providers --> DB
    Billing --> DB
    Messaging --> DB
    Messaging --> Queue[SQS through Floci]
    Queue --> Notification
    Notification --> DB
    Notification --> Mail[Mailpit]
~~~

Planned technology choices:

- Java 21
- A current stable Spring Boot release selected during foundation work
- PostgreSQL and Flyway
- Spring JDBC or JdbcClient for correctness-sensitive transitions
- Testcontainers for integration tests
- Docker Compose for the complete local environment
- AWS SDK for Java v2 with Floci for local SQS compatibility
- Mailpit for local email inspection
- Micrometer and Prometheus
- k6 for reproducible workload scenarios

Redis, Kafka, Kubernetes, and microservices are not part of the initial design.
They can be considered only after measurements identify a problem they solve.

## Simulated billing

A later billing extension keeps groups free. Providers receive a usable Free
plan and may upgrade to a simulated Pro subscription.

The illustrative Pro price is PHP 499 per provider organization per month. It
is a preliminary product assumption, not validated market pricing.

No card, bank, GCash, or Maya credentials are collected. The extension will
simulate subscription state, entitlements, a concurrency-safe Free usage
limit, and duplicate subscription events. It will not create invoices or a
fake checkout.

Paid status must never purchase verification, organic rank, or access to
better-quality group requests.

## Local-first development

Normal development and automated tests require no AWS account.

| Local component | Possible future AWS equivalent |
| --- | --- |
| Spring Boot container | ECS/Fargate or another container runtime |
| PostgreSQL container | RDS PostgreSQL or Aurora PostgreSQL |
| Floci SQS and DLQ | Amazon SQS and DLQ |
| Mailpit SMTP | Amazon SES |
| Prometheus | Managed Prometheus or selected CloudWatch metrics |
| Local configuration | Parameter Store and Secrets Manager |

Floci is intentionally limited to SQS. PostgreSQL runs directly because its
transaction and locking behavior is part of the product.

## Documentation map

- [Product Scope](docs/product-scope.md)
- [Domain Model](docs/domain-model.md)
- [Architecture](docs/architecture.md)
- [API Contract](docs/api-contract.md)
- [Consistency and Concurrency](docs/consistency-and-concurrency.md)
- [Testing Strategy](docs/testing-strategy.md)
- [Architecture Decision Records](docs/adr/README.md)

## Repository status

| Area | Status |
| --- | --- |
| Product and market framing | Documented |
| Architecture baseline | Documented |
| Application foundation | Bootstrapped |
| Database schema and migrations | Not started |
| Planning and marketplace workflows | Not started |
| Billing simulation | Not started |
| Concurrency evidence | Not started |
| Performance measurements | Not started |

No benchmark results are published because no reproducible benchmark has been
run.

## Local workflow

The wrapper is the normal build entry point. With Java 21 installed, these
commands build and run the application:

~~~bash
./mvnw clean verify
./mvnw spring-boot:run
~~~

Docker Compose remains planned for the complete local environment. The
application currently starts without external services.
