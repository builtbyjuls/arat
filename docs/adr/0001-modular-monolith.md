# ADR-0001: Start as a Modular Monolith

- Status: Accepted
- Date: 2026-09-17

## Context

Arat? includes several business capabilities: identity, groups, planning, marketplace negotiation, providers, matching, simulated billing, messaging, and notifications.

The most important workflows cross several of those capabilities. Publishing a request begins with private plan state. Selecting an offer changes marketplace and plan state together. Provider confirmation must preserve one active match per plan. Splitting these operations across separately deployed services would introduce distributed transactions, compensating workflows, more failure modes, and substantial local infrastructure.

The system is designed and maintained by one engineer. Clear domain boundaries
and proven correctness matter more than the number of deployable services.

At the same time, a single undifferentiated codebase would allow controllers, SQL, and domain rules to become coupled. The project needs visible boundaries even when deployment remains simple.

## Options considered

### Option A: Microservices from the start

Deploy groups, planning, marketplace, providers, billing, and notifications independently, with each owning a database or schema and communicating through APIs or events.

Benefits:

- Independent deployment and scaling are available immediately.
- Network boundaries make some forms of coupling obvious.

Costs:

- Selection and confirmation require distributed coordination or compensation.
- Local setup, testing, observability, deployment, and schema evolution become significantly larger.
- Operational complexity would exist before measured demand justifies it.
- More code would focus on infrastructure plumbing rather than the product's central correctness problem.

### Option B: Unstructured monolith

Build one Spring Boot application and allow any package to call any repository or query any table.

Benefits:

- Lowest initial ceremony.
- Simple local execution.

Costs:

- Ownership of rules and data becomes unclear.
- Cross-module changes can bypass application use cases and invariants.
- Later extraction or focused testing becomes difficult.

### Option C: Modular monolith

Build one deployable Spring Boot application and one PostgreSQL database while dividing code and table ownership into explicit business modules.

Benefits:

- Local ACID transactions remain available for correctness-sensitive workflows.
- One process and one database keep local development inexpensive.
- Module APIs and boundary tests expose coupling.
- Modules can be extracted later if evidence supports it.

Costs:

- Boundaries require discipline because the runtime does not enforce a network boundary.
- A process-level failure affects all modules.
- Deployment and horizontal scaling apply to the whole application.

## Decision

Arat? will start as a modular monolith using Java 21 and Spring Boot.

The application will:

- Produce one deployable artifact.
- Use one PostgreSQL database.
- Organize source by business module, not by global technical layer.
- Give each module ownership of its tables and internal packages.
- Expose cross-module operations through explicit public application APIs and events.
- Prohibit one module from directly querying another module's tables.
- Verify dependency rules with automated architecture tests.
- Use local database transactions when a workflow legitimately spans closely related module state.
- Deliver cross-cutting asynchronous work through a transactional outbox.

The initial modules are Identity, Groups, Planning, Marketplace, Providers, Matching, Billing, Messaging, and Notification.

This decision does not require an interface around every class, a separate build artifact per module, or a generic repository abstraction.

## Consequences

### Positive

- The one-active-match invariant can be enforced with one PostgreSQL transaction and constraint.
- Local development requires only the application and a small Docker Compose stack.
- End-to-end workflows are easier to run and debug.
- Module boundaries remain visible in packages, APIs, schema naming, and tests.
- The design uses the simplest architecture that meets the current requirements.

### Negative

- The application is deployed and scaled as one unit.
- Expensive work in one module can affect the process if resource pools are not bounded.
- Internal boundaries can erode unless architecture tests and code structure prevent shortcuts.
- Extracting a module later requires replacing local calls and transactions with explicit remote contracts and failure handling.

### Mitigations

- Keep asynchronous consumers on bounded executors.
- Measure database queries and queue lag by module or use case.
- Keep public module APIs small and keep internal packages inaccessible.
- Avoid sharing persistence entities between modules.
- Store cross-module event payloads as versioned contracts.

## Revisit criteria

Reconsider this decision only when evidence shows at least one of the following:

- A module requires a deployment or release cadence that cannot be supported by the monolith.
- A workload needs independent scaling and resource isolation after in-process tuning is exhausted.
- A failure-prone integration needs process isolation to protect core planning and matching operations.
- Legal or security requirements demand separate data ownership or isolation.
- Measured deployment or recovery time becomes unacceptable.

Extraction is not justified by hypothetical scale or module count alone.
