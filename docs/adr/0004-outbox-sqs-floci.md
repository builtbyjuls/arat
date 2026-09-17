# ADR 0004: Transactional Outbox with SQS and Floci

- Status: Accepted
- Date: 2026-09-17

## Context

Arat? performs state transitions that must remain correct even when secondary work fails. Examples include:

- a request is published and matched providers need notification;
- an organizer selects an offer and the provider needs notification;
- a provider confirms or declines a pending match;
- a confirmation deadline expires;
- a simulated subscription changes state.

Sending a message directly inside a database transaction cannot make PostgreSQL and a message broker commit atomically. If the broker call succeeds and the database rolls back, consumers observe an event that never happened. If the database commits and the process stops before sending, the event is lost.

The project also needs a local-first workflow with no AWS account. A future AWS deployment should not require rewriting domain logic.

Amazon SQS standard queues provide at-least-once delivery. A message can be received more than once, so consumers must be idempotent. [Amazon documents this delivery contract explicitly](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html).

## Decision

Use a PostgreSQL transactional outbox, the `arat-notifications` Amazon SQS standard queue, its `arat-notifications-dlq` dead-letter queue, and an idempotent consumer inbox.

Use Floci to emulate only SQS and the DLQ in local development and integration tests. Use AWS SDK for Java 2.x for both Floci and real SQS.

### Producer transaction

The application writes these records in one PostgreSQL transaction:

1. the domain state change;
2. its audit entry where required;
3. one immutable outbox row for each integration event;
4. the idempotent command result where the API contract requires it.

If the transaction rolls back, none of them exists. No SQS call occurs inside the business transaction.

### Outbox relay

One or more relay workers:

1. claim eligible unpublished rows in a short transaction using a bounded lease and PostgreSQL locking such as `FOR UPDATE SKIP LOCKED`, then commit the claim;
2. serialize the versioned envelope;
3. call `SendMessage` through AWS SDK v2;
4. mark the row published with a separate conditional update only after SQS acknowledges the send;
5. release or expire the lease on failure and retry with bounded backoff and jitter.

A process can stop after SQS accepts a message but before PostgreSQL records publication. The relay will send it again. This is an intentional at-least-once result.

The relay must not hold a database transaction open during an unbounded network call. Claim leases and update conditions must allow another worker to recover abandoned work.

### Consumer transaction

Each message includes a unique `eventId`. A consumer:

1. validates envelope type and schema version;
2. begins a PostgreSQL transaction;
3. inserts `eventId` into a consumer-specific inbox table with a unique constraint;
4. if the insert is new, applies the durable consumer effect;
5. commits;
6. deletes the SQS message only after commit.

If the inbox insert conflicts, the event was already processed. The consumer treats the duplicate as successful and deletes the message.

If the process stops after commit but before deletion, SQS delivers the message again and the inbox makes the replay harmless.

### Queue and DLQ

Use a standard source queue and a standard DLQ. Configure:

- long polling;
- a visibility timeout longer than normal processing, with controlled extension when needed;
- a receive count that allows transient retries before redrive;
- DLQ retention longer than source retention;
- monitoring for oldest-message age, source backlog, and any DLQ message.

The DLQ is an isolation and diagnosis mechanism, not an archive. Redrive is manual or explicitly controlled until the underlying problem is corrected. AWS describes DLQs and redrive policies in its [SQS DLQ guidance](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html).

### Event envelope

The durable envelope includes:

```json
{
  "eventId": "uuid",
  "eventType": "OfferSelected",
  "schemaVersion": 1,
  "occurredAt": "RFC-3339 UTC instant",
  "aggregateType": "Plan",
  "aggregateId": "uuid",
  "aggregateVersion": 7,
  "traceId": "opaque trace identifier",
  "payload": {}
}
```

Payloads contain stable identifiers and the minimum data consumers require. Consumers load current sensitive data through authorized application paths rather than copying unnecessary member details into the queue.

Event schemas are backward compatible within a version. An incompatible change creates a new schema version and a transition plan.

### Local and AWS configuration

```text
LOCAL AND TEST
AWS SDK v2 -> endpoint override -> Floci :4566
standard source queue -> standard DLQ
placeholder credentials

FUTURE AWS
AWS SDK v2 -> regional Amazon SQS endpoint
standard source queue -> standard DLQ
workload role credentials
```

The endpoint override is configuration. Domain and application modules do not import Floci APIs. The [AWS SDK v2 endpoint guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html) documents endpoint overrides for local service implementations.

## Guarantees

This decision provides:

- atomic commit of domain state and intent to publish;
- eventual publication while PostgreSQL and the relay recover;
- at-least-once delivery;
- safe duplicate handling for durable consumer effects;
- isolation of persistent failures in a DLQ;
- horizontal relay and consumer processing without a single in-memory leader;
- the same application protocol in local development and a future AWS environment.

It does not provide:

- exactly-once network delivery;
- global ordering;
- immediate notification;
- atomic commit across PostgreSQL and SQS;
- protection from a defective consumer that performs a non-idempotent external action before recording its inbox result;
- proof that Floci reproduces every SQS behavior.

Any external side effect that cannot join the inbox transaction, such as SMTP delivery, needs its own idempotency or reconciliation strategy. A message can still be delivered twice around an ambiguous external response.

## Floci scope

Floci is selected because its documented SQS implementation supports the required standard queue, visibility, long polling, DLQ, and redrive operations, and because it integrates with Testcontainers. Sources:

- [Floci SQS service](https://floci.io/floci/services/sqs/)
- [Floci Testcontainers for Java](https://floci.io/floci/testcontainers/java/)
- [Floci releases](https://github.com/floci-io/floci/releases)
- [Floci issues](https://github.com/floci-io/floci/issues)

The image is pinned to an exact version or digest. Emulator tests are supplemented by an isolated real-SQS compatibility suite before AWS deployment.

Floci will not emulate RDS, SES, ECS, IAM, or observability services for this project. PostgreSQL, Mailpit, the application container, and Prometheus already provide clearer local behavior.

## Alternatives considered

### Publish directly after committing

Rejected. A process stop between database commit and send loses the event unless another durable recovery record exists, which recreates an outbox less explicitly.

### Publish inside the database transaction

Rejected. PostgreSQL and SQS do not share the transaction. Network latency would also keep locks open and increase contention.

### Use only a database work queue

Reasonable for the first process, but rejected as the target boundary. SQS provides buffering, visibility timeout, redrive, independent consumers, and a credible future managed path. PostgreSQL remains the source of truth through the outbox.

### Use Kafka

Rejected. The MVP needs task-like asynchronous delivery and retries, not retained high-throughput event streams, consumer replay, or partition coordination. Kafka would add substantial local and operational complexity without solving a current problem.

### Use Redis as a queue or lock manager

Rejected. Redis is not otherwise required. PostgreSQL enforces the business invariants and SQS handles asynchronous delivery.

### Use SQS FIFO

Rejected. Most events do not require total order, and idempotency is required even with FIFO. Aggregate versions and conditional writes handle stale events. A specific ordered workflow can justify FIFO in a later ADR.

### Use an in-memory Spring event listener

Rejected for integration events. Process failure would lose work, multiple replicas would not share delivery, and retry state would not be durable. In-process domain events may still organize work within one transaction if they do not imply durable asynchronous delivery.

### Emulate every future AWS service with Floci

Rejected. It would increase coupling and create false confidence. Only the selected SQS boundary has a concrete current use.

## Consequences

### Positive

- User-facing transactions are independent of SMTP latency.
- Database and publication intent cannot diverge at commit.
- Failure behavior becomes observable and testable.
- Local development stays free and accountless.
- Migration to real SQS is mostly configuration at the adapter boundary.
- The design handles duplicate delivery rather than claiming exactly-once delivery.

### Negative

- Events are eventually, not immediately, delivered.
- Outbox and inbox tables need indexes, retention, and monitoring.
- Duplicate delivery is normal and every consumer must account for it.
- Operators need DLQ inspection and redrive procedures.
- Schema evolution becomes an explicit responsibility.
- Floci adds one pinned development dependency and requires compatibility review.

## Required implementation evidence

Before this ADR is considered implemented, the repository must contain passing tests that prove:

- a rolled-back domain transaction leaves no outbox row;
- a committed domain change always has its expected outbox row;
- relay restart publishes previously committed rows;
- a stop after SQS send but before publish marking creates a duplicate, not a loss;
- repeated delivery produces one consumer effect;
- consumer commit followed by a stop before message deletion is safe;
- repeated failures move a message to the DLQ;
- two relay workers do not permanently strand claimed work;
- event schema version handling rejects or isolates unsupported messages visibly.

No benchmark or reliability result is claimed by accepting this ADR.

## Related documents

- [Testing Strategy](../testing-strategy.md)
