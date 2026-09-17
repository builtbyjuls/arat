# Consistency and Concurrency

Status: Target design

This document defines the correctness contract for Arat?. It describes what the implementation and tests must prove. It does not claim that the proof already exists.

## 1. Consistency model

PostgreSQL is the source of truth for all domain state. Arat? uses PostgreSQL's default `READ COMMITTED` isolation together with:

- Unique, check, and foreign-key constraints
- Narrow row locks on aggregate roots
- Conditional `UPDATE` statements
- Immutable request and offer-term snapshots
- Database time for expiration decisions
- Idempotency records for retried commands
- Transactional outbox and consumer inbox records

Arat? does not rely on Java `synchronized`, an in-memory mutex, Redis, or queue ordering to enforce a durable invariant. Those mechanisms cannot protect multiple application instances or repair a process crash.

Global `SERIALIZABLE` isolation is intentionally not the default. It would make all callers handle serialization retries while obscuring which resources actually contend. Individual workflows can be reconsidered if tests expose an invariant that cannot be expressed safely with the current tools.

See [ADR-0002](adr/0002-postgresql-coordination.md).

## 2. Canonical state model

The [Domain and Data Model](domain-model.md#state-machines) owns the canonical
transition diagrams. This document adds concurrency rules without defining a
second set of state machines.

The concurrency-sensitive facts are:

- `MATCH_PENDING` means one match is
  `AWAITING_PROVIDER_CONFIRMATION`; `MATCHED` means one match is `CONFIRMED`.
- At most one request is current in `OPEN` or `SELECTION_PENDING` for a plan.
- Publishing a replacement supersedes the current request and leaves the plan
  `OPEN_FOR_OFFERS` in one transaction.
- Offer terms are sealed after submission. A `SELECTED` offer is terminal even
  if its pending match later fails.
- A timeout, decline, or pending cancellation returns the request to `OPEN` only
  while its offer deadline remains in the future.
- Historical request versions, offers, and terminal match attempts remain
  readable but cannot become eligible again.
- At most one match may ever reach `CONFIRMED` for a plan. Cancelling a
  confirmed match ends that plan; a new attempt uses a new plan.

## 3. Invariants

### 3.1 Database-enforced invariants

The exact migration may evolve, but the schema must enforce equivalent rules.

```sql
CREATE UNIQUE INDEX uq_published_request_plan_version
    ON planning_published_request (plan_id, request_version);

CREATE UNIQUE INDEX uq_published_request_one_current
    ON planning_published_request (plan_id)
    WHERE state IN ('OPEN', 'SELECTION_PENDING');

CREATE UNIQUE INDEX uq_match_one_active_per_plan
    ON marketplace_match (plan_id)
    WHERE state IN ('AWAITING_PROVIDER_CONFIRMATION', 'CONFIRMED');

CREATE UNIQUE INDEX uq_match_one_confirmed_ever_per_plan
    ON marketplace_match (plan_id)
    WHERE confirmed_at IS NOT NULL;

CREATE UNIQUE INDEX uq_vote_one_per_member_offer
    ON marketplace_offer_vote (offer_id, account_id);

CREATE UNIQUE INDEX uq_request_recipient_once
    ON marketplace_request_recipient (published_request_id, provider_id);

CREATE UNIQUE INDEX uq_inbox_event_once
    ON messaging_inbox (consumer_name, event_id);

CREATE UNIQUE INDEX uq_idempotency_scope_key
    ON idempotency_record (actor_id, operation, idempotency_key);

CREATE UNIQUE INDEX uq_outbox_business_key
    ON messaging_outbox (business_key);
```

Foreign keys must also prove that:

- An offer references an existing published request and provider.
- A match references an offer whose request belongs to the same plan. If that relationship cannot be expressed by one foreign key, redundant keys plus composite unique constraints are preferred over application-only validation.
- A vote references a current group membership or records the membership identity needed for audit.
- Provider staff actions reference an active provider membership at decision time.

Check constraints protect local facts such as:

- Minimum headcount is positive and not greater than maximum headcount.
- Minimum price is non-negative and not greater than maximum price.
- An offer expiration follows its submission time.
- A confirmation deadline follows match creation.
- Currency is a supported three-letter code.
- Money is stored in integer minor units, never floating point.

### 3.2 Application-enforced rules with database guards

The application explains these rules with domain-specific errors, while conditional SQL and constraints remain the final guard:

- Only an organizer of the plan's group may publish or select.
- Only a current group member may vote.
- Only active provider staff may submit, withdraw, confirm, or decline for that provider.
- An offer may be submitted only against the current request in `OPEN` state before its offer deadline.
- An offer may be selected only while `SUBMITTED`, unexpired, and attached to the plan's current request version.
- A match may be confirmed only while `AWAITING_PROVIDER_CONFIRMATION` and before its confirmation deadline.
- Selection uses the server's 30-minute confirmation policy and is rejected
  unless the full window ends before the requested outing starts.
- A plan with an active match cannot select another offer.
- Once the billing extension is enabled, simulated subscription limits
  affect new outbound offers but never hide existing offers or matches.

Authorization and mutable-state checks belong in the same database transaction as the change when a concurrent update could invalidate the decision.

Group authority is serialized through the `group_account` root row. Every command that
depends on current group membership or organizer authority locks that row
before checking the membership. Transfer, removal, leave, and invite-acceptance
commands take the same lock before changing membership or incrementing the
group version. A removal or role transfer therefore cannot commit between an
authorization check and its protected write.

This intentionally serializes short authority-dependent writes within one
small group. The target group size is 4 to 20 people; a more granular
membership-lock scheme is justified only if measurements show this root lock
is material.

Provider staff and eligibility mutations use the same rule on the
`provider_organization` root. Provider commands lock it before checking active
staff membership, verification state, or `eligibility_version`.

## 4. Transaction boundaries

### 4.1 Create or replace a member preference

Within one transaction:

1. Resolve the group and plan identifiers without locking.
2. Lock the group root and verify that the actor remains an active member.
3. Lock the plan and verify that collaboration is still allowed.
4. For the first write, require `If-None-Match: *` and insert one preference
   row with version 1. The unique `(plan_id, account_id)` key rejects a race.
5. For a replacement, lock the existing preference, compare `If-Match` with
   its version, replace the preference snapshot, and increment its version.

A failed precondition returns HTTP 412. Different members have separate
preference versions; the plan version is not their edit token.

### 4.2 Publish a request version

Within one transaction:

1. Claim the command's idempotency key when supplied.
2. Resolve and lock the plan's group root.
3. Lock the plan row, compare the supplied plan ETag, and confirm that the
   actor remains the organizer.
4. Validate that the plan has no active match.
5. Read the current plan data and construct the provider-visible snapshot.
6. Mark the previous request version as `SUPERSEDED`, if present.
7. Insert the next immutable version.
8. Mark remaining submitted offers from the old version as `NOT_SELECTED`.
9. Set the plan to `OPEN_FOR_OFFERS` and increment the plan version.
10. Calculate a bounded set of eligible providers and insert recipient records.
11. Insert one provider notification outbox row per recipient.
12. Complete the idempotency record with the new request identifier.

If any step fails, none of the state is committed.

Publication does not lock every candidate provider while holding the plan row.
Instead, each recipient stores the provider's `eligibility_version` observed by
the matching query. Brief access, notification delivery, and offer submission
all require that version to equal the provider's current version and require
the provider to remain active and verified. A concurrent suspension increments
the version, immediately making a stale recipient unusable even if its insert
commits later.

### 4.3 Submit an offer

Within one transaction:

1. Claim the idempotency key.
2. Resolve the explicit provider path context, plan, and request identifiers
   without locking.
3. Lock the provider root, verify active staff membership, and verify the
   provider remains active and verified.
4. Lock the plan followed by the published request.
5. Use database time to verify under those locks that the request remains the
   plan's current version, is `OPEN`, and is before its offer deadline.
6. Verify the provider has an active recipient authorization whose stored
   eligibility version equals the locked provider's current version.
7. If the billing extension is enabled, verify the simulated
   subscription entitlement or usage limit.
8. Lock any current submitted offer revision for that provider and request.
9. Insert the immutable offer terms with state `SUBMITTED`, superseding the provider's earlier submitted revision when applicable.
10. If metering is enabled, atomically increment any Free-plan usage.
11. Insert `OfferSubmitted` into the outbox.
12. Complete the idempotency record.

Before the billing extension, every verified recipient may submit without metering. Once the
billing extension is enabled, the entitlement check and usage increment cannot
be separated into two transactions; otherwise concurrent Free-plan offers
could exceed the limit.

### 4.4 Cast or change a vote

Within one transaction:

1. Resolve the group, plan, request, and offer identifiers without locking.
2. Lock the group root and verify current membership.
3. Lock the plan, published request, and offer in the global order.
4. Verify that the offer belongs to the plan and remains visible for voting.
5. For the first vote, require `If-None-Match: *` and insert version 1. The
   unique `(offer_id, account_id)` key rejects a race.
6. For a replacement, lock the existing vote, compare `If-Match`, update its
   value, and increment its version.

Voting is advisory. It never creates a match, even if a vote reaches a
majority. A stale or failed create precondition returns HTTP 412 rather than
silently accepting a last-writer-wins update.

### 4.5 Select an offer

Within one transaction:

1. Claim the idempotency key.
2. Resolve the group, selected offer's provider, and plan without locking.
3. Lock the group root and verify that the actor remains organizer.
4. Lock the provider root and verify it remains active and verified.
5. Lock the plan row, compare the supplied plan ETag, and verify that the plan
   is `OPEN_FOR_OFFERS`.
6. Lock the current published request and then the selected offer.
7. Read database time once for the decision.
8. Verify that the selected offer is `SUBMITTED`, unexpired, and belongs to the current `OPEN` request version.
9. Verify that the offer and active recipient store the provider's current
   eligibility version.
10. Transition the request to `SELECTION_PENDING` and the offer to `SELECTED`.
11. Insert an `AWAITING_PROVIDER_CONFIRMATION` match with the current provider
    eligibility version and a fixed confirmation deadline.
12. Conditionally transition the plan to `MATCH_PENDING` and increment its
    version.
13. Insert `MatchConfirmationRequested` into the outbox.
14. Complete the idempotency record.

The partial unique index on active matches is the final defense against two active matches. A uniqueness violation caused by a competing selection is translated into a stable conflict response, not exposed as an internal server error.

Illustrative conditional transition:

```sql
UPDATE marketplace_offer
SET state = 'SELECTED', selected_at = :decision_time
WHERE id = :offer_id
  AND state = 'SUBMITTED'
  AND expires_at > :decision_time
  AND provider_eligibility_version = :provider_eligibility_version
  AND published_request_id = :current_request_id;
```

Exactly one updated row is required. Zero rows means the offer is no longer selectable.

### 4.6 Provider confirmation

Within one transaction:

1. Claim the idempotency key.
2. Resolve the provider organization from the match and resolve the plan
   identifier without locking.
3. Lock the provider root, plan row, published request, affected offers in ID
   order, and match row, in that order.
4. Verify active staff membership and that the provider remains active and
   verified.
5. Verify that the match's stored provider eligibility version equals the
   locked provider's current version.
6. Read database time once after the locks are acquired.
7. Verify that the match is `AWAITING_PROVIDER_CONFIRMATION` and `decision_time < confirmation_deadline`.
8. Transition the match to `CONFIRMED`; the selected offer remains `SELECTED` as immutable history.
9. Transition the plan to `MATCHED` and increment its version.
10. Transition the request to `CLOSED` and mark other submitted offers for it as `NOT_SELECTED`.
11. Insert `MatchConfirmed` into the outbox.
12. Complete the idempotency record.

Confirmation does not call the notification service and does not send email in the transaction.

### 4.7 Confirmation timeout

A scheduler may discover candidate match identifiers without holding long transactions. Each candidate is processed in its own transaction:

1. Resolve and lock the plan row.
2. Lock the published request, every affected `SUBMITTED` offer in identifier
   order, and then the match row.
3. Read database time once after locks are acquired.
4. If the match is still `AWAITING_PROVIDER_CONFIRMATION` and its deadline is at or before that time, transition it to `TIMED_OUT`.
5. Leave the selected offer in its terminal `SELECTED` state.
6. If the request offer deadline remains in the future, return the request to
   `OPEN` and the plan to `OPEN_FOR_OFFERS`.
7. Otherwise apply the shared close rule: change the request to `CLOSED`, mark
   every remaining `SUBMITTED` offer `NOT_SELECTED`, and return the plan to
   `COLLABORATING`.
8. Increment the plan version and insert `MatchTimedOut` notification work into
   the outbox.

If confirmation already won, the timeout is a no-op. If timeout won, a late confirmation receives a stable expired response.

### 4.8 Shared request close rule

Manual closure, request-deadline expiration, plan cancellation, confirmation,
and the expired-deadline branch of decline, timeout, or pending-selection
cancellation use one rule:

1. Lock any required group or provider root for authorization.
2. Lock the plan, the current published request, all affected `SUBMITTED`
   offers in identifier order, and any pending match in the global order.
3. Recheck state, authority, the supplied ETag when required, and database
   time.
4. Move every remaining `SUBMITTED` offer for the request to `NOT_SELECTED` in
   the same transaction that moves the request to `CLOSED` or `CANCELLED`.
5. Preserve a previously selected offer as terminal `SELECTED` history.
6. Update and version the plan, append the appropriate outbox event, and
   complete the idempotency record.

Manual request closure is allowed only from `OPEN`; an organizer uses pending
match cancellation while the request is `SELECTION_PENDING`. When a decline,
timeout, or pending cancellation reopens a request before its offer deadline,
other `SUBMITTED` offers remain eligible and are not terminalized.

## 5. Time rules

Application-server clocks do not decide offer or confirmation validity.

- PostgreSQL `clock_timestamp()` supplies the decision time after required locks are acquired.
- All durable timestamps are stored as `timestamptz` in UTC.
- Human display uses the plan's IANA time zone, such as `Asia/Manila`.
- Validity intervals are half-open: a resource is valid while `decision_time < expires_at`.
- At exactly the deadline, the offer or confirmation window is expired.
- A transaction that acquires the relevant lock and records a decision before the deadline may commit after the deadline; its recorded database decision time remains authoritative.

Tests use a controllable application clock for non-authoritative presentation logic, but expiry integration tests use PostgreSQL time or a database abstraction designed specifically for tests. Production correctness cannot depend on changing the application clock.

## 6. Lock order and deadlock prevention

When a transaction needs multiple locks, it obtains them in this order:

1. Idempotency record for the command, if any
2. `group_account` root when the command changes group membership or depends on current
   group authority
3. `provider_organization` root when the command changes or depends on provider
   membership or eligibility
4. Plan
5. Published request
6. Offers, ordered by identifier, then the caller's vote when applicable
7. Match
8. Outbox rows created by the transaction

Every command or scheduler that changes request, offer, or match state locks the
plan first after any required group and provider roots, then locks request,
offers in ID order, and match as applicable. Withdrawal and expiration workers
follow this same rule; they do not lock an offer first. The plan row is the
serialization point for one plan's marketplace transitions.

Selection resolves identifiers first, then follows group, provider, plan,
request, and offer order. Confirmation follows provider, plan, request, offers,
and match order. Timeout needs no membership or provider decision, so it
follows plan, request, offers, and match. Suspension locks the provider before
reconciling affected plans. No path locks a plan and then waits for a required
group or provider root.

Candidate matching is the exception to locking every provider before the plan:
it records provider eligibility versions and never treats the recipient row
alone as authority. This avoids locking an unbounded provider set while keeping
suspension immediately effective at access and delivery boundaries.

Bulk workers:

- Process small bounded batches.
- Use `FOR UPDATE SKIP LOCKED` only for work claiming, never as the sole enforcement of a domain invariant.
- Sort identifiers before locking several records.
- Commit each bounded batch before network delivery.
- Retry a detected PostgreSQL deadlock a small, bounded number of times only for commands known to be safe and idempotent.

The database constraint remains authoritative if a future code path accidentally violates the documented order.

## 7. Race outcomes

| Race | Required outcome |
|---|---|
| Two devices select different offers for one plan | The transaction that first transitions the locked plan creates the active match. The other receives conflict. One active match exists. |
| Same selection command is retried | The same idempotency key and payload return the original match. No second match or event is created. |
| Same idempotency key is reused with a different offer | The command is rejected as an idempotency-key mismatch. |
| Offer withdrawal versus organizer selection | The plan and offer transition predicates allow one valid transition. The loser receives a stable non-selectable or already-selected result. |
| Offer selection versus offer deadline | Database decision time decides. At or after the deadline selection fails even if the expiry worker has not run. |
| Republish request versus submit offer | Publication locks the plan and supersedes the old version. Submission must validate current version in its transaction. An old-version offer cannot become selectable. |
| Provider confirmation versus confirmation timeout | Both lock plan then match and inspect database time. Only one pending-state transition can update the row. |
| Provider confirmation versus organizer cancellation | Both serialize through the plan row. Product transition rules decide based on the committed state seen by the second transaction. |
| Group member creates or replaces one vote concurrently | A first-write unique key or matching ETag lets one write win; stale competitors receive HTTP 412 and one current vote remains. |
| Membership removal or organizer transfer races a protected command | Both take the group root first. The protected command either commits under the authority it locked or observes the new membership state and fails without a domain write. |
| A provider qualifies through several matching paths | Unique request/provider recipient plus deterministic outbox business key leaves one durable recipient and one initial notification job. |
| Request publication versus provider suspension | Publication may retain a recipient with the old eligibility version, but access, notification, and offer submission fail after suspension. If delivery wins first, already sent mail cannot be recalled. |
| Provider suspension versus offer selection | Selection succeeds only while recipient and offer eligibility versions equal the locked provider version. Otherwise the offer remains historical and cannot create a match. |
| Suspension and reinstatement versus confirmation | Incremented eligibility versions prevent an old pending match from being revived after reinstatement; reconciliation cancels the pending attempt. |
| Duplicate simulated subscription event (billing extension) | Billing inbox uniqueness applies the event once and returns success for the duplicate. |
| Two Free-tier offer submissions reach the monthly limit (billing extension) | The usage counter is conditionally incremented in the same transaction; no more than the allowance succeeds. |

## 8. Idempotency protocol

Commands with externally visible side effects require an `Idempotency-Key`, including:

- Publish request
- Submit or withdraw offer
- Select offer
- Confirm or decline match
- Simulated subscription change

The durable idempotency record contains:

- Actor scope
- Operation name
- Client key
- Canonical request hash
- Processing state
- Result resource identifier
- Stable response status and minimal response payload
- Creation and expiration timestamps

Protocol:

1. Insert the key in the same transaction as the command.
2. If it already exists with the same request hash and is complete, return the stored result.
3. If it exists with a different hash, return `409 Conflict`.
4. If a prior transaction rolled back, its idempotency insert also rolled back and a retry can proceed.
5. If an in-progress record is observed because of an unusual recovery path, do not execute the command independently; return retryable status or reconcile it.

For an idempotent command that also carries `If-Match`, a completed same-hash
replay returns at step 2 before evaluating the now-stale resource ETag. A new
command evaluates its precondition under the documented domain locks.

Keys are scoped by actor and operation so unrelated users cannot collide. Sensitive request bodies are not stored solely for idempotency.

## 9. Outbox and consumer idempotency

The notification outbox closes the database-to-queue failure gap. A domain change and its notification work are committed together. Release 1 uses one standard notification queue and one dead-letter queue; provider matching itself remains database-backed application work.

Each outbox row has a deterministic `business_key`. Initial provider notice
keys use `RequestPublished:{request_id}:{provider_id}` so retrying publication
or repairing delivery for an existing recipient cannot create another initial
notification job. Recovery never adds recipients to an already-published
request version; a broader audience requires a new request version.

Illustrative claim query:

```sql
SELECT id, event_type, aggregate_id, payload
FROM messaging_outbox
WHERE published_at IS NULL
  AND available_at <= clock_timestamp()
ORDER BY created_at, id
FOR UPDATE SKIP LOCKED
LIMIT :batch_size;
```

Network publishing does not occur while these rows remain locked. A relay first claims a batch with a lease or claim token, commits, publishes outside the transaction, then records success. A relay crash can produce a duplicate message, so consumers must deduplicate.

A consumer transaction:

1. Inserts `(consumer_name, event_id)` into its inbox.
2. If the insert conflicts, it acknowledges the duplicate without repeating work.
3. Performs its local database side effect.
4. Commits inbox and side effect together.
5. Acknowledges the SQS message after commit.

Email itself cannot participate in the database transaction. Notification delivery therefore records attempts and uses a stable notification identifier. A crash after Mailpit or a real email provider accepts a message can still cause a repeated delivery. The message identifier and content make such duplicates observable; the system does not claim exactly-once email.

## 10. Optimistic concurrency for ordinary edits

Editable records such as groups, plan drafts, member preferences, provider
profiles, listings, and offer votes carry a numeric version. APIs expose that
version through an ETag or explicit field. Updates use:

```sql
UPDATE planning_plan
SET title = :title,
    version = version + 1,
    updated_at = clock_timestamp()
WHERE id = :id
  AND version = :expected_version;
```

Zero updated rows returns HTTP `412 Precondition Failed` with the current
representation. The server does not silently overwrite a concurrent edit.

This mechanism is not used in place of the stronger plan lock and unique constraint required during offer selection.

## 11. Concurrency test plan

Concurrency claims require deterministic PostgreSQL integration tests with Testcontainers.

### 11.1 Required tests

1. `selectingTwoOffersCreatesOneActiveMatch`
   - Prepare one plan and two submitted offers.
   - Start two transactions behind a barrier.
   - Select different offers simultaneously.
   - Assert one success, one conflict, one active match, one plan in `MATCH_PENDING`, and one confirmation event.

2. `duplicateSelectionReturnsOriginalMatch`
   - Send the same key and payload concurrently.
   - Assert both callers observe the same match identifier and only one outbox event exists.

3. `sameIdempotencyKeyWithDifferentPayloadIsRejected`
   - Reuse a completed key for another offer.
   - Assert conflict and unchanged state.

4. `withdrawAndSelectHaveOneWinner`
   - Coordinate withdrawal and selection at the offer transition.
   - Assert an allowed final state and no active match when withdrawal wins.

5. `confirmationAndTimeoutHaveOneWinner`
   - Use a controlled deadline and coordinated transactions.
   - Assert either confirmed or timed out, never both, with plan and offer states consistent.

6. `oldRequestOfferCannotBeSelectedAfterRepublish`
   - Race republishing with old-offer selection.
   - Assert no match references a superseded request.

7. `submitOfferCannotCommitAgainstSupersededRequest`
   - Resolve an old request for offer submission, pause before its plan lock,
     and publish the next request version.
   - Continue submission and assert that no `SUBMITTED` offer references the
     superseded version.

8. `duplicateQueueDeliveryAppliesConsumerEffectOnce`
   - Deliver one event twice.
   - Assert one inbox row and one visible notification job.

9. `providerSuspensionInvalidatesRacingRecipient`
   - Publish a request while suspending an otherwise eligible provider.
   - Assert the result is either no recipient or a stale-version recipient that
     cannot authorize brief access, notification delivery, or offer submission.

10. `providerSuspensionPreventsStaleSelectionAndConfirmation`
    - Race suspension with selection, then suspend and reinstate before a
      pending-match reconciliation runs.
    - Assert that old offer and match eligibility versions cannot select or
      confirm after either version change.

### 11.2 Billing extension test

11. `freeTierOfferLimitCannotBeExceededConcurrently`
   - Submit more simultaneous offers than remaining allowance.
   - Assert the exact allowance succeeds.

The Free-tier quota test becomes required only when the billing extension is implemented. It
is not a gate for the core request-first release.

### 11.3 Test mechanics

- Use latches, barriers, and transaction hooks rather than arbitrary sleeps.
- Use separate database connections for competing operations.
- Capture all returned results and exceptions.
- Assert database state after every competing transaction completes.
- Repeat race tests enough times to expose scheduling differences, but retain deterministic coordination.
- Never replace PostgreSQL with H2 for these tests.

## 12. Correctness evidence checklist

Before presenting the project as concurrency-safe, the repository must contain:

- Flyway constraints corresponding to documented invariants
- Conditional SQL with affected-row assertions
- Integration tests for every required race
- Idempotency tests for same and mismatched payloads
- Outbox and inbox duplicate-delivery tests
- Failure tests for queue and email unavailability
- A reproducible load-test scenario and raw output
- An explanation of any observed deadlocks, retries, or bottlenecks

Until those artifacts exist and pass, this document is a design specification rather than proof.
