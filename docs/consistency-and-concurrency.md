# Consistency and Concurrency

Status: M1 collaboration proof and provider verification decisions, suspension,
restoration, and requirement finalization implemented; marketplace, messaging, billing, and load evidence
remain target design.

This document defines the correctness contract for Arat?. M1 group and planning
races have executable PostgreSQL evidence in
`CollaborationRaceOutcomesIT`, and the M1 HTTP journey and OpenAPI contract have
executable evidence in `MilestoneOneJourneyIT`. Marketplace, messaging,
billing, and load claims remain design targets until their implementation and
tests exist.

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
- A candidate window references its one owning plan. M1 replaces requirements
  by retiring omitted window rows rather than deleting referenced rows.

A partial unique index, not a check constraint, permits at most one `PENDING`
invitation for one `(group_id, invitee_account_id)` pair.

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
staff membership, verification state, or `eligibility_version`. M2 mutations
that cannot change `provider_id` use `FOR NO KEY UPDATE`, so recipient
foreign-key `KEY SHARE` locks remain compatible. Separate-connection
PostgreSQL tests must prove the actual behavior, not just cite a lock matrix.
Every verification-state transition increments provider version and monotonic
eligibility version exactly once. Profile replacement increments only provider
version; exact command replay increments neither. Restoration never revives an
old eligibility version.

## 4. Transaction boundaries

### 4.1 M1 group membership and invitation commands

Commands resolve identifiers, claim their required idempotency key, and lock the
group root before testing active membership or organizer authority. Invitation
creation locks that same root, marks an effectively expired pending invite
`EXPIRED`, then relies on the pending-invite unique index before inserting. It
does not advance the group version. Acceptance locks the group root, validates
the token digest, invitation state, expiry, and bound account, then consumes
the invitation and creates or reactivates membership as `MEMBER` in one
transaction. Unknown, wrong-account, expired, revoked, and consumed tokens use
the same `INVITATION_UNAVAILABLE` outcome.

Leave, removal, acceptance, and organizer transfer change membership or roles
and advance the group version once. Transfer promotes the target and demotes
only its caller; it does not alter other organizers. The final-organizer check
is made under the group lock. Exact completed idempotent replay is resolved
before current ETag validation where the command has one.

### 4.2 Create or replace a member preference

Within one transaction:

1. Resolve the group and plan identifiers without locking.
2. Lock the group root and verify that the actor remains an active member.
3. Lock the plan and verify that collaboration is still allowed and that the
   supplied positive `basisPlanVersion` equals the current plan version.
4. For the first write, require `If-None-Match: *` and insert one preference
   row with version 1. The unique `(plan_id, account_id)` key rejects a race.
5. For a replacement, lock the existing preference, compare `If-Match` with
   its version, replace the preference snapshot, and increment its version.

A failed HTTP version precondition returns 412; a stale basis returns 409
`REQUIREMENT_VERSION_CHANGED`. Different members have separate preference
versions; the plan version is not their edit token. A preference stores the
client-supplied basis and later plan replacement makes it stale without
rewriting or deleting it.

### 4.3 Replace a requirement draft

Within one transaction, lock the group root, verify organizer authority, lock
the plan, compare its `If-Match`, reject a cancelled plan, validate the full
replacement, retain known candidate-window IDs, retire omitted IDs, and advance
the plan version once. The transaction does not finalize or publish a provider
request; those are planned M2 operations. M2 allows requirement and preference
writes in `COLLABORATING` and `OPEN_FOR_OFFERS`. They affect private state only;
they never mutate or close current request N. A changed draft can be finalized
and published directly as N+1 while N remains current until commit.

### 4.4 Finalize and publish a request version (M2)

Finalization claims the required idempotency key, locks group then plan,
verifies organizer authority, plan `If-Match`, and `COLLABORATING` or
`OPEN_FOR_OFFERS`, and copies one active candidate window and provider-publishable
terms into an immutable finalization. The chosen offer deadline must satisfy
`decision_time < offer_deadline < chosen_start`. Planning owns this operation;
it neither changes plan state nor increments its version. Advisory current/stale
preference counts and warnings never silently change organizer terms.

Marketplace owns publication. Within one PostgreSQL transaction, through public
module APIs:

1. Authenticate, validate the command, and claim the required idempotency key.
   Exact completed replay returns before current ETag/state validation; changed
   plan ID, finalization ID, or supplied version under the key conflicts.
2. Resolve the group and plan identifiers, lock the group root through Groups,
   and verify current organizer authority.
3. Lock the plan through Planning, compare `If-Match`, and require
   `COLLABORATING` or `OPEN_FOR_OFFERS`.
4. Lock the current request, if any, after the plan. Require a same-plan
   finalization whose basis equals the locked plan version. Read PostgreSQL
   decision time after locks; reject an elapsed deadline.
5. Matching queries Providers
   for distinct `VERIFIED` candidates by exact supported category and configured
   service-area code, returning observed eligibility versions ordered by
   provider UUID ascending. Radius is provider-visible context only.
6. Deduplicate before applying the externally configurable cap (default 100,
   range 1-500). Zero candidates or more than the cap rejects publication
   without committed domain state; never truncate the audience.
7. Mark N `SUPERSEDED` and non-actionable if present. Allocate the next
   monotonically increasing request version and insert N+1 in `OPEN`, with an
   allowlist copy of the immutable finalization and `MATCHED_POOL` distribution.
8. Store one `MATCH_RULE` recipient per distinct candidate with its captured
   eligibility version. Install the same-plan current pointer, set
   `OPEN_FOR_OFFERS`, and increment plan version.
9. Append audit and per-recipient outbox rows through Messaging: published
   events for N+1 and superseded events for N's existing audience.
10. Complete idempotency with the immutable resource reference, original status,
    headers, and only small fixed-shape reconstruction metadata.

Any failure rolls back requests, plan transition, recipients, audit, all outbox
rows, and idempotency claim/completion together. M2 has no offer rows or offer
cascades; M3 extends replacement to terminalize old submitted offers.

Publication does not lock the candidate provider set after locking the plan.
Recipients capture versions from the matching query. Every later request read
requires active provider staff, `VERIFIED` state, an `ACTIVE` recipient, and
captured/current eligibility equality. A concurrent suspension can make a
recipient unusable even if publication commits afterward. Restoration advances
the version again and never revives it. Profile edits change future matching
only. M3 offer submission and M4 delivery apply the same eligibility fence.

### 4.4.1 Reads, closure, and cancellation (M2, planned)

Active group members may read current requests and bounded history; outsiders
receive the existing private-resource not-found response. Provider detail and
feed use explicit provider context and the access guards above, including for
terminal history. Cross-provider, non-recipient, and stale-eligibility reads do
not disclose existence. Effective actionable state requires a current `OPEN`
request before its database-time deadline. M2 has no expiry worker.

Marketplace pages its recipients by `created_at DESC`, then request ID
descending, using opaque versioned provider-bound cursors, default limit 20,
maximum 100. Authorization belongs before page limiting; there is no
post-cursor page filtering and no category/area filter. Planning supplies only
allowlisted request projections through its public API. Terminal history may
remain visible when still authorized.

Manual closure claims idempotency, locks group then plan then request, verifies
organizer authority and plan ETag, and accepts only `OPEN`. It marks the request
`CLOSED`, clears the current pointer, returns the plan to `COLLABORATING`, and
increments plan version. Plan cancellation retains its route and M1 behavior,
and in M2 also accepts `OPEN_FOR_OFFERS`, atomically marking the current request
`CANCELLED`, clearing the pointer, and making the plan terminal. Marketplace
owns request-aware cancellation's route transaction and delegates state changes
to Planning, avoiding a dependency cycle. Both commands preserve history and
commit per-recipient terminal events, audit, and idempotency completion together.
Offer and match cascades remain M3 work.

### 4.5 Submit an offer

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

### 4.6 Cast or change a vote

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

### 4.7 Select an offer

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

### 4.8 Provider confirmation

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

### 4.9 Confirmation timeout

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

### 4.10 Shared request close rule (M3 extension)

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

Application-server clocks do not decide finalization deadlines, request
actionability, offer validity, or confirmation validity.

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
follows plan, request, offers, and match. In M3, suspension locks the provider
before reconciling affected plans. No path locks a plan and then waits for a required
group or provider root.

Candidate matching is the exception to locking every provider before the plan:
it records provider eligibility versions and never treats the recipient row
alone as authority. This avoids locking an unbounded provider set while keeping
suspension immediately effective at access and (in M4) delivery boundaries.
Recipient inserts still acquire foreign-key `KEY SHARE` locks; compatible
provider `FOR NO KEY UPDATE` root mutations are required so these implicit
locks do not recreate a plan-to-provider wait cycle.

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
| Two publications use one plan ETag | One commits a new current request and advances the plan; a different-key competitor receives 412. Same-key exact replay returns the original result with no extra rows. |
| Finalization races requirement replacement | Group/plan locks order the operations; a finalization made before replacement becomes stale and publication rejects its basis. |
| Publication races membership removal or organizer transfer | Group-root order preserves the locked authority decision or rejects the later publication without domain writes. |
| Publication or replacement fails after any durable write | Request, plan, audience, audit, outbox, and idempotency all roll back, leaving N current if it existed. |
| Replacement races closure or cancellation | Group then plan then request locks permit one valid versioned outcome; no mixed current pointer or partially terminal audience events remain. |
| Profile replacement races matching | Matching observes one committed profile and captures its eligibility version; an existing grant is never rewritten. |
| Provider root mutation races recipient foreign-key insertion | `FOR NO KEY UPDATE` and `KEY SHARE` remain compatible in real PostgreSQL; neither a hidden provider wait nor a lock-order cycle is introduced. |
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
| Request publication versus provider suspension | M2 publication may retain a stale-version recipient, but access fails after suspension and remains denied after restoration. M3 submission and M4 delivery apply the same fence; already sent M4 mail cannot be recalled. |
| Provider suspension versus offer selection | Selection succeeds only while recipient and offer eligibility versions equal the locked provider version. Otherwise the offer remains historical and cannot create a match. |
| Suspension and reinstatement versus confirmation | Incremented eligibility versions prevent an old pending match from being revived after reinstatement; reconciliation cancels the pending attempt. |
| Duplicate simulated subscription event (billing extension) | Billing inbox uniqueness applies the event once and returns success for the duplicate. |
| Two Free-tier offer submissions reach the monthly limit (billing extension) | The usage counter is conditionally incremented in the same transaction; no more than the allowance succeeds. |

## 8. Idempotency protocol

M1 requires an `Idempotency-Key` for group creation, invitation creation and
revocation, invitation acceptance, self-leave, member removal, organizer
transfer, plan creation, and plan cancellation. Later commands retain their
own documented requirements: M2 provider creation, verification submission,
decision, suspension, restoration, requirement finalization, provider-request
publication and closure; M3 offer submission and withdrawal, selection, match
confirmation, decline, completion, and cancellation; and deferred simulated
subscription changes.
Requirement replacement, preference writes, and M2 provider profile replacement
use only their required version preconditions.

The durable idempotency record contains:

- Actor scope
- Operation name
- Client key
- Canonical request hash
- Processing state
- Result resource identifier
- Stable response status, replay body, and allowlisted response headers
- Creation and expiration timestamps

Protocol:

1. Canonicalize and validate the command before hashing; invite acceptance
   hashes the token digest, never a raw token.
2. Insert the key in the same transaction as the command.
3. If it already exists with the same request hash and is complete, return the
   stored result before checking a now-stale ETag.
4. If it exists with a different hash, return `409 IDEMPOTENCY_KEY_REUSED`.
5. If a prior transaction rolled back, its idempotency insert also rolled back and a retry can proceed.
6. If an in-progress record is observed because of an unusual recovery path, do not execute the command independently; return retryable status or reconcile it.

For an idempotent command that also carries `If-Match`, a completed same-hash
replay returns at step 3 before evaluating the now-stale resource ETag. A new
command evaluates its precondition under the documented domain locks.

Keys are scoped by actor and operation so unrelated users cannot collide. Replay
state is at most 16 KB, retained for seven days, and stores only `ETag` and
`Location` headers with a 2 KB combined limit. Sensitive request bodies are not
stored solely for idempotency; in particular, invite-create replay reconstructs
its token rather than storing it.

M2 finalization/publication never store their full response JSON in `ReplayState`.
They retain the immutable resource ID through the existing resource reference,
original status and allowlisted headers, plus only small fixed-shape metadata
if needed. Exact replay reloads immutable content and reconstructs the original
representation, including publication's original `OPEN` result after later
supersession, closure, or cancellation. Valid maximum multibyte content and
attribute keys containing `token`, `secret`, `authorization`, `password`, or
`cookie` must work without increasing 16 KB, weakening sensitive-key detection,
or special-casing arbitrary attributes in the generic component.

## 9. Outbox and consumer idempotency

The planned M2 outbox captures durable notification intent only. Messaging
owns immutable versioned envelopes and an append API that must join the caller
transaction; it cannot independently commit. No relay, AWS SDK, SQS, Floci,
inbox, SMTP, email rendering, delivery retry, or DLQ exists in M2. M3 appends
offer and match events; M4 introduces the delivery mechanisms below.

Each outbox row has a unique deterministic `business_key`. M2 event types and
keys are:

| Event | Per-recipient business key | Audience |
| --- | --- | --- |
| `ProviderRequestPublished` | `ProviderRequestPublished:{requestId}:{providerId}` | New request's fixed recipients |
| `ProviderRequestSuperseded` | `ProviderRequestSuperseded:{requestId}:{providerId}` | Replaced request's fixed recipients |
| `ProviderRequestClosed` | `ProviderRequestClosed:{requestId}:{providerId}` | Closed request's fixed recipients |
| `ProviderRequestCancelled` | `ProviderRequestCancelled:{requestId}:{providerId}` | Cancelled request's fixed recipients |

The envelope has event ID/type, schema version, occurrence time, aggregate
identity/version, trace ID, and minimal payload identifiers. No private fields
or arbitrary organizer text are copied into events. Request, plan transition,
recipients, audit, idempotency, and all events commit or roll back together.
Recovery uses existing recipients and deterministic keys; it never reruns
matching to expand an audience.

The remaining relay, consumer, and email design is M4, planned.

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

M2's planned tests cover publication/finalization, provider identity and
eligibility, recipient reads, and outbox capture. The complete M2 gates are in
the [testing strategy](testing-strategy.md#milestone-2-contract-and-failure-matrix-planned).
They include actual PostgreSQL root/FK lock compatibility and same-key replay
of maximum multibyte snapshots with sensitive-looking valid attribute keys.
The offer/match tests below begin in M3 and queue tests in M4.

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

The M1 collaboration artifacts named above provide executable evidence for that
scope. The marketplace, messaging, billing, and load artifacts remain required
before those later claims become proof rather than design targets.
