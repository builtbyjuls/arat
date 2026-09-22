# Arat?

Arat? is a group-first marketplace for casual outings in the
Philippines.

An existing barkada, work team, family, or club agrees on when it is available,
how many people are joining, where it can go, and what it can spend. The group
can then publish an anonymized requirement to relevant local providers.
Providers return structured, expiring offers. Members compare and vote, the
organizer selects one offer, and the provider confirms the match.

The name comes from Filipino backslang for "tara", or "let's go."

Milestones 0 and 1 provide a runnable application foundation and an implemented
private collaboration workflow. Marketplace workflows remain planned, and the
documents describe the evidence required before making performance or
scalability claims beyond the implemented foundation.

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

The implemented foundation uses Java 21, Spring Boot, PostgreSQL and Flyway,
Testcontainers, Docker Compose, Actuator, Prometheus metrics, and GitHub
Actions verification. The following remain planned where noted:

- Java 21
- A current stable Spring Boot release selected during foundation work
- PostgreSQL and Flyway
- Spring JDBC or JdbcClient for correctness-sensitive transitions
- Testcontainers for integration tests
- Docker Compose for the complete local environment
- AWS SDK for Java v2 with Floci for local SQS compatibility (planned with messaging)
- Mailpit for local email inspection (planned with email delivery)
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

Normal M0 and M1 development and verification need Docker but no AWS account,
cloud credentials, Floci, Mailpit, or paid service. Docker runs PostgreSQL for
host-run application development and Testcontainers tests; the full Compose
stack also runs the application and Prometheus.

| Planned local component | Possible future AWS equivalent |
| --- | --- |
| Spring Boot container | ECS/Fargate or another container runtime |
| PostgreSQL container | RDS PostgreSQL or Aurora PostgreSQL |
| Floci SQS and DLQ | Amazon SQS and DLQ |
| Mailpit SMTP | Amazon SES |
| Prometheus | Managed Prometheus or selected CloudWatch metrics |
| Local configuration | Parameter Store and Secrets Manager |

Floci is intentionally limited to SQS when messaging exists. It is absent from
the M0 stack because no outbox, queue adapter, or consumer exists yet. Mailpit
is likewise absent until email delivery exists. PostgreSQL runs directly
because its transaction and locking behavior is part of the product.

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
| Application foundation | Implemented: runnable Spring Boot, local auth, health, metrics, Compose, and CI smoke checks |
| Database schema and migrations | Implemented: Flyway baseline and PostgreSQL test foundation |
| Planning workflow | Implemented: private groups, invitations, collaborative plans, requirement replacement, member preferences, and cancellation |
| Marketplace workflows | Not started |
| Billing simulation | Not started |
| Concurrency evidence | Implemented for M1 collaboration; marketplace concurrency work remains planned |
| Performance measurements | Not started |

No benchmark results are published because no reproducible benchmark has been
run.

## Runnable local workflow

Prerequisites are Java 21, Bash, Docker with Docker Compose v2, `curl`,
`unzip`, `jq`, and a SHA-256 utility such as `sha256sum` or `shasum`. The
wrapper is the normal build entry point. No AWS account, cloud credentials,
Floci, Mailpit, or paid service is needed for M0 and M1.

### Test lanes

Run fast unit tests with:

~~~bash
./mvnw test
~~~

Run the normal verification lane with unit tests, PostgreSQL Testcontainers
integration tests, architecture tests, security-profile tests, migration tests,
and readiness tests:

~~~bash
./mvnw clean verify
~~~

### Host-run application

Start only PostgreSQL in one terminal, then run the application on the host in
another terminal:

~~~bash
docker compose up --wait postgres
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
~~~

The local profile defaults to PostgreSQL at `localhost:5432/arat` with the
fake `arat` username and password. Override `ARAT_DATABASE_URL`,
`ARAT_DATABASE_USERNAME`, and `ARAT_DATABASE_PASSWORD` when needed.

The local bearer tokens are deliberately fake and unsafe for production. They
are available only in the `local` profile and select fixed development
accounts. For example, select the organizer account with:

~~~bash
curl -H 'Authorization: Bearer arat-local-owner-token' \
  http://localhost:8080/api/v1/dev/whoami
~~~

Use `arat-local-member-token` or `arat-local-outsider-token` to select the other
fixed local accounts. The tokens and probe are unavailable outside the `local` profile. The `prod`
profile cannot be combined with `local` or `compose`. Stop the host application
with `Ctrl+C`, then stop the database with `docker compose stop postgres`.

### Milestone 1 collaboration journey

The PostgreSQL-backed M1 journey uses the three local fake accounts through
their public HTTP bearer-token boundary. It creates a private group, accepts an
account-bound invitation, creates and revises a badminton plan, records member
preferences, proves private-plan isolation, and cancels the plan while keeping
authorized reads available. It also verifies idempotent replays, stale ETags,
audit records, and the executable OpenAPI contract.

Run it from a clean checkout with Docker available:

~~~bash
./mvnw verify -Dit.test=MilestoneOneJourneyIT
~~~

The test starts PostgreSQL through Testcontainers, applies Flyway migrations,
and starts the application test context. It needs no AWS account, Floci, Redis,
Kafka, external email service, or paid service.

### Full Compose stack

Start PostgreSQL, the application, and Prometheus together:

~~~bash
docker compose up --build --wait
~~~

Service URLs are:

- Application: `http://127.0.0.1:8080`
- Liveness: `http://127.0.0.1:8080/actuator/health/liveness`
- Readiness: `http://127.0.0.1:8080/actuator/health/readiness`
- Prometheus metrics: `http://127.0.0.1:8080/actuator/prometheus`
- Prometheus UI: `http://127.0.0.1:9090`
- PostgreSQL: `127.0.0.1:5432`

After Compose reports healthy services, verify the fake local actor:

~~~bash
curl -H 'Authorization: Bearer arat-local-owner-token' \
  http://127.0.0.1:8080/api/v1/dev/whoami
~~~

Override ports for an isolated local run:

~~~bash
ARAT_APP_PORT=18080 ARAT_POSTGRES_PORT=15432 docker compose up --build --wait
~~~

The Compose defaults use the deliberately fake `arat` PostgreSQL username and
password. Override `ARAT_DATABASE_NAME`, `ARAT_DATABASE_USERNAME`, and
`ARAT_DATABASE_PASSWORD` when needed; no local secret file is required.

Stop the full stack while preserving named volumes:

~~~bash
docker compose down
~~~

Remove the named PostgreSQL and Prometheus volumes only when a local reset is
intentional:

~~~bash
docker compose down --volumes
~~~

### Isolated smoke check

To verify a clean runtime startup without touching the default Compose project
or its volumes, run the isolated smoke check from the repository root:

~~~bash
scripts/smoke-foundation.sh
~~~
