# ADR-0002: Use PostgreSQL as the Coordination Authority

- Status: Accepted
- Date: 2026-09-17

## Context

Several Arat? operations can arrive concurrently from different application instances:

- Two devices may select different offers for the same plan.
- A provider may withdraw an offer while an organizer selects it.
- A provider confirmation may race its timeout worker.
- A request may be republished while a provider submits an offer.
- Duplicate client or queue requests may be retried.

The resulting invariants are attached to durable relational state. In particular, a plan may have at most one active match, an expired offer cannot be selected, and a retried command must not create another result.

The coordination mechanism must continue to work after an application restart and with more than one application instance.

## Options considered

### Option A: In-memory locks

Use Java locks keyed by plan or offer identifier.

Benefits:

- Simple within one process.
- Low lock acquisition latency.

Costs:

- Does not coordinate multiple instances.
- Lock state disappears on restart.
- Correctness depends on every code path using the same local lock registry.
- A database constraint is still needed for durable protection.

### Option B: Redis distributed locks

Acquire a lease in Redis before changing PostgreSQL state.

Benefits:

- Coordinates several application instances.
- Can reduce concurrent entry to a critical section.

Costs:

- Adds another stateful system and a second source of timing decisions.
- Lease expiry, fencing, process pauses, and partial failures must be handled correctly.
- The lock and PostgreSQL transaction are not atomic.
- PostgreSQL still needs constraints to prevent stale or bypassed lock holders from violating invariants.

### Option C: Serialize commands through a queue

Route all commands for a plan through one ordered consumer.

Benefits:

- Can reduce concurrent processing for a key.
- Fits asynchronous workflows.

Costs:

- User-facing selection and confirmation become dependent on queue latency and availability.
- Queue ordering is not a substitute for database constraints or idempotency.
- Consumer crashes and redelivery still require conditional state transitions.
- Partitioning and hot-key behavior add complexity.

### Option D: PostgreSQL constraints and transactions

Use the database that already owns the state to arbitrate transitions.

Benefits:

- The invariant and data commit atomically.
- Unique constraints remain correct across processes and restarts.
- Row locks and conditional updates give precise contention control.
- Database time gives one authority for deadlines.

Costs:

- Hot plan rows serialize competing commands.
- Poor lock order can produce deadlocks.
- PostgreSQL-specific behavior must be tested against PostgreSQL.

## Decision

PostgreSQL will be the coordination authority.

Arat? will use:

- PostgreSQL `READ COMMITTED` isolation by default.
- A partial unique index to allow only one active match per plan.
- Unique constraints for request versions, votes, idempotency keys, inbox events, and fan-out delivery.
- Explicit row locks on aggregate roots for multi-step state transitions.
- Conditional updates that include the expected current state and deadline.
- A documented lock order for workflows that touch several rows.
- PostgreSQL `clock_timestamp()` as the authority for offer and confirmation deadlines.
- `JdbcClient` and visible SQL for correctness-sensitive transitions.
- Testcontainers for concurrency and transaction tests.

Redis locks, Java locks, and queue ordering will not be required for domain correctness. Release 1 uses SQS only for notifications after a transaction commits. Provider matching remains database-backed application work.

### M2 publication coordination (implemented)

M2 adds provider eligibility, finalization, request publication/access, and
transactional outbox capture. Relay and queue delivery begin in M4. M2 commands
claim idempotency before group, plan, and request locks; Marketplace coordinates
publication through public module APIs in one PostgreSQL transaction. Planning
owns immutable finalizations and request versions; Providers owns eligibility;
Matching owns rules only; Messaging owns the transaction-joining outbox append.

Matching captures observed eligibility versions without locking the candidate
provider set after the plan. Later access requires active staff, VERIFIED state,
ACTIVE recipient, and equal stored/current eligibility versions. Every actual
verification transition advances provider and eligibility versions once;
restoration never revives old grants. Profile replacement advances only provider
version and affects future matching.

Provider root mutations that cannot change `provider_id` use `FOR NO KEY UPDATE`
to remain compatible with recipient foreign-key `KEY SHARE` locks. Tests must
prove both lock orders with separate PostgreSQL connections; a documented lock
matrix alone is insufficient. PostgreSQL also enforces immutable snapshot and
ordered child content, same-plan current pointers, unique request versions,
and deterministic per-recipient outbox business keys.

## Consequences

### Positive

- Data and its invariant commit or roll back together.
- Competing selections have a deterministic, testable winner.
- The architecture has no additional distributed lock service.
- Deadline behavior is independent of application-server clock skew.
- Correctness can be demonstrated through migrations, SQL, and integration tests.

### Negative

- Contention is concentrated on PostgreSQL for the same plan.
- Critical SQL is PostgreSQL-specific and cannot be accurately tested with an in-memory database.
- Long transactions or inconsistent lock order could reduce throughput.
- Callers must receive clear conflicts when a conditional transition updates zero rows.

### Mitigations

- Keep transactions narrow and prohibit network calls inside them.
- Lock plan before request, offer, and match rows.
- Process bulk work in bounded batches.
- Translate uniqueness and conditional-update failures into domain responses.
- Track lock waits, deadlocks, transaction latency, and connection-pool usage.
- Use deterministic concurrency tests with separate database connections.

## Revisit criteria

Reconsider or supplement this decision when measured evidence shows:

- A single plan or provider becomes a sustained database hot key after query and transaction tuning.
- Required coordination spans independent databases because a module has been extracted.
- Cross-region writes become a real requirement.
- A workload can safely use weaker consistency and needs a different store.

Even then, database constraints remain the last defense for invariants stored in PostgreSQL. A Redis lock or queue may optimize contention, but it must not become the sole correctness mechanism.
