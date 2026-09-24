# Testing Strategy

## Purpose

Arat? is a collaborative planning and provider-matching system. Its most important claims are about correctness under concurrent and repeated requests, not about the number of screens it exposes. The test suite must therefore prove the business invariants with the same PostgreSQL behavior used by the application.

This document defines the target testing strategy for the planned marketplace,
messaging, billing, and load work. M1 group and planning correctness already
have executable PostgreSQL evidence in `CollaborationRaceOutcomesIT`, while the
HTTP journey and OpenAPI contract are covered by `MilestoneOneJourneyIT`.
M2 now has focused PostgreSQL suites plus `MilestoneTwoJourneyIT` for the full
HTTP and executable OpenAPI journey. UI1 next adds the planned mobile web
client gates below; no browser evidence is claimed yet. M3 adds offers, votes,
selection, confirmation, and matches. M4 adds relay, AWS SDK, SQS/Floci, inbox,
SMTP, email rendering, delivery retries, and DLQ tests. No M2 test needs those M4
components.

## Quality goals

The test suite must provide evidence that:

- only one offer can win a single-destination plan;
- an offer cannot be selected after it expires or is withdrawn;
- an offer made against an old request version cannot win after that request is superseded;
- provider confirmation cannot arrive after the confirmation deadline and revive a timed-out match;
- repeated commands and messages do not create duplicate business effects;
- database changes and integration events are not split across different commits;
- at-least-once message delivery does not duplicate durable consumer effects or
  in-application notification rows; SMTP delivery may still repeat around an
  ambiguous provider response;
- provider subscription limits remain correct under concurrent submissions
  once the billing extension is enabled;
- the application continues to enforce these rules at realistic concurrency.

## Test layers

| Layer | Purpose | Infrastructure | Typical scope |
| --- | --- | --- | --- |
| Unit | Check pure rules quickly | JUnit 5, AssertJ, no Spring context | State transitions, eligibility, pricing display, request validation |
| Application | Check one use case through its application boundary | Spring test slice with controlled ports | Authorization, command handling, error mapping |
| Database integration | Prove transactions, constraints, and SQL behavior | PostgreSQL Testcontainer plus Flyway | Offer selection, request versions, idempotency, quota ledger |
| Messaging integration | Prove AWS SDK v2 interaction and delivery behavior | Floci Testcontainer with SQS and DLQ | Publish, receive, redelivery, visibility timeout, DLQ |
| Component | Exercise HTTP with real milestone dependencies | M1-M3: application and PostgreSQL; M4 adds Floci and Mailpit | Collaboration, publication, then publish-to-match and delivery journeys |
| Load | Measure behavior and expose contention | k6 against the composed application | Browse traffic, offer bursts, hot selection, async backlog |

H2 is not an acceptable substitute for PostgreSQL in tests involving transactions, row locking, partial indexes, exclusion constraints, JSON behavior, or concurrent updates. A test that proves an invariant only on H2 does not prove the application design.

## Test taxonomy

Use these Maven groups so local and automated runs can choose feedback speed
without changing test semantics:

- `unit`: no network or container dependency;
- `integration`: PostgreSQL and, where required, Floci through Testcontainers;
- `component`: application process plus all required infrastructure;
- `load`: k6 scripts run explicitly, never as part of the normal unit test task.

The default build should run unit and integration tests. Component and load tests may run in separate CI jobs, but a milestone must not be committed as complete when a required correctness suite fails.

## Determinism rules

- Inject `java.time.Clock`; do not read the JVM system clock directly in domain or application code.
- Use a fixed or controllable clock only for application-only timestamps that
  do not authorize an action.
- Use PostgreSQL time for deadline and subscription-entitlement predicates that
  coordinate concurrent writers. Integration tests obtain a database-time
  baseline and create clearly past or future bounds around it rather than
  assuming the workstation clock is synchronized.
- Supply UUIDs through an injectable generator when an assertion depends on identity.
- Seed any randomized test and print the seed on failure.
- Do not use `Thread.sleep` to coordinate races. Use barriers, latches, futures, and bounded awaits.
- Assert final database state and durable side effects, not the order in which threads happened to finish.
- Give every asynchronous assertion a finite timeout and a useful failure message.

## Milestone 2 contract and failure matrix (implemented)

These are acceptance requirements, not test results. Use PostgreSQL
Testcontainers with the real Flyway migrations wherever database behavior
matters, deterministic coordination, and separate competing connections.

| Contract | Required test and durable evidence |
| --- | --- |
| Provider creation is atomic | Create and replay; inject a failure between organization and creator membership. Exactly one provider and one `ACTIVE ADMIN` membership commit, or neither does. |
| Provider authority is scoped | Exercise `ADMIN`, `STAFF`, outsider, and operator-without-membership in explicit provider routes. Platform role grants no staff access. Tests may seed staff rows; no invitation/removal API is needed. |
| Profile replacement is bounded | Test trimmed display name 1-120, unique categories from M1 with declared 1-10 bound, and 1-20 unique trimmed area codes of 1-64 characters; replacement uses provider ETag, increments provider version only, and preserves existing grants. |
| Verification transitions fence grants | Cover UNVERIFIED/REJECTED to PENDING by ADMIN; PENDING acceptance/rejection, VERIFIED suspension, SUSPENDED restoration by operator. Each transition increments both versions once; exact replay increments neither. Invalid states and roles use documented errors. |
| Evidence and reasons are bounded | Test 1-10 unique printable ASCII references of 1-256 characters, optional decision notes at most 500, trimmed suspension reason 1-500; references are never uploaded or exposed to request recipients. |
| Matching is exact and bounded | Match VERIFIED providers by category and exact configured area code, deduplicate before UUID ordering and cap, capture observed eligibility version; radius never drives distance logic. Test zero, cap, cap+1, default 100, configured 1 and 500, and invalid configuration bounds. Zero/excess rejects with no domain writes. |
| Finalization preserves organizer intent | Select one active same-plan window; copy its values and all publishable terms at the locked plan version. Reject retired/foreign windows and non-future or start-equal/later deadlines using PostgreSQL time. State/version do not change. |
| Preferences remain advisory | Capture bounded counts of current/stale submissions and no-current/stale-input warnings; do not silently change headcount, budget, schedule, or other terms. |
| Finalization freshness is enforced | Race finalization with requirement replacement using group then plan locks. Publish only the matching basis version; reject stale/foreign finalizations without partial state. |
| Open-request edits stay private | Edit requirements and preferences in OPEN_FOR_OFFERS, finalize, and directly republish. N remains immutable/current until N+1 commits; cancellation rejects later writes. |
| Snapshot allowlist is enforced | Assert exact provider response fields, excluding group ID/name, plan title, identities, preferences, attendance, private notes, employer and contacts. Deliberately publishable free text survives; tests must not assume automatic PII redaction. |
| Database immutability is real | Attempt direct SQL changes to snapshot columns and insertion/update/deletion of ordered children after creation. Reject each; guarded lifecycle changes preserve content. |
| Publication is one transaction | Inject failures after request insertion, superseding N, recipient insertion, audit, any outbox append, and before idempotency completion/commit. Request, plan pointer/version, recipients, audit, all events, and key records roll back together; N remains current on replacement failure. |
| Outbox append joins its caller | Prove caller rollback removes appended rows, absence of a caller transaction is rejected, versioned envelope is immutable, and deterministic event business keys reject duplicates. There is no relay or delivery effect in M2. |
| Publication/terminal fan-out is fixed | One published event per distinct new recipient; one corresponding superseded/closed/cancelled event per old recipient with documented business keys and no private fields. Audience never expands during recovery. |
| Replay supports every valid snapshot | Finalize, publish, and replay maximum valid multibyte content exceeding 16 KB and valid attribute keys containing token, secret, authorization, password, and cookie. Replay state stays compact; generic limits/guards remain unchanged. |
| Replay preserves original publication | Replay after supersession, closure, and cancellation; original status, headers, and OPEN representation return. Changed plan ID, finalization ID, or supplied plan version under the key returns 409. No extra rows/events appear. |
| Competing publication has one winner | Race same-key exact publication and different-key publication with one ETag. Exact duplicates reconstruct one result; different keys yield one commit and one 412. No duplicate version or current pointer remains. |
| Authority races serialize | Race publication with organizer transfer or membership removal. Group-root order permits a protected commit or rejects the later unauthorized write without domain changes. |
| Eligibility fencing survives races | Pause publication after matching, suspend, and resume insert/commit; stale recipient cannot read. Restore and recheck: old grant remains denied. Profile edits affect only future matching. |
| PostgreSQL root/FK locks are compatible | On separate connections, hold provider FOR NO KEY UPDATE while inserting a recipient FK, and hold recipient KEY SHARE while mutating provider non-key state. Assert real progress with bounded waits and lock diagnostics, not sleeps or a lock-matrix assumption. |
| Reads preserve privacy | Active group member reads current/history; outsider gets private-resource 404. Provider requires active staff, VERIFIED, ACTIVE recipient, equal eligibility versions; wrong provider, non-recipient, revoked or stale grant never leaks existence. |
| Feed pages are recipient-owned | Equal created-at timestamps use request-ID descending tie-break; opaque versioned cursor is provider-bound, limit defaults 20/max 100. No post-page filtering or category/area filters; authorized terminal history is retained. |
| Database time controls actionability | Before/at/after offer deadline, the effective flag changes correctly even while stored state stays OPEN and no expiry worker runs. |
| Closure/cancellation are atomic | Close only OPEN; clear pointer, return plan to COLLABORATING, preserve history. Cancel OPEN_FOR_OFFERS through existing route; request and plan become CANCELLED atomically. Race with replacement and assert one coherent ETag/state/event outcome. |
| HTTP contract is complete | Cover every M2 problem code/status, privacy and role failure, ETag ordering, validation, and replay; journey from provider create/verify through publish, read, replace, close, and cancel. Executable OpenAPI arrives with implementation. |

The normal `./mvnw clean verify` lane includes the M0-M2 regression coverage.
M2 proves durable outbox capture only; it makes no offer, relay, queue, email,
or notification-delivery claim.

## UI1 web client gates (planned)

The [Mobile Web Client Contract](web-client.md) defines the client decisions.
UI1 requires the following evidence before its status becomes implemented:

| Lane | Required evidence |
| --- | --- |
| Backend | Focused PostgreSQL Testcontainers tests for discovery privacy, roles, bounded stable pagination, indexed query plans, finalization basis, and exact pending submissions; normal `./mvnw clean verify` regression lane |
| Contract | Checked-in executable OpenAPI snapshot, including local identity probe; deterministic untracked TypeScript generation and drift detection against the running backend |
| Frontend | Independent npm install, lint, Vitest unit/component tests, normal production and local-demo builds; no fake tokens or actor selector in production output |
| HTTP | Exact headers, response metadata, first-write/stale ETags, same-intent transport retry, duplicate clicks, key misuse, original replay versus fresh state, safe malformed/unknown Problem Details |
| Identity/privacy | Probe before rendering, direct reload and server rediscovery, actor-switch and late-response isolation, 401/403/private 404 handling, token-free history/storage, no private provider fields or evidence leakage |
| Runtime | Isolated same-origin Compose smoke; SPA deep-link fallback, non-SPA missing-asset/API failures, proxy header preservation and token-path log redaction, bounded readiness and resource cleanup |
| Browser | Playwright mobile Chromium journey at 390 CSS pixels plus restrained desktop smoke; representative shell, list, form, detail, confirmation and error patterns at 320, 360 and 390 with no horizontal page overflow |
| Accessibility | Axe on representative patterns plus keyboard, labels, field errors, visible focus, navigation/dialog focus restoration, zoom and non-hover manual checks |

The browser journey covers organizer/member invitations and collaboration,
provider creation/submission, operator exact-submission decision, eligible
recipient privacy, finalization/publication/replacement/history, closure and
cancellation. It must include stale ETag recovery, deliberate retries, reloads,
and actor changes. It does not demonstrate M3 offers or M4 delivery.

Maven must not install Node or execute frontend checks; frontend unit/build
checks must not run Maven. Contract and Compose orchestration are explicit
separate steps. Use semantic browser locators, deterministic readiness and
bounded waits, without arbitrary sleeps or retries that hide flakes. Browser
outputs, traces, reports, and generated types stay untracked; sensitive token
or evidence inputs must not leak into committed artifacts. Browser tests
supplement PostgreSQL transaction/concurrency proof. Automated accessibility
checks supplement manual review and do not claim complete accessibility.

## Invariant test matrix

| Invariant | Primary proof | Required competing actions | Expected evidence |
| --- | --- | --- | --- |
| One winning offer per plan | Database integration test | Select two eligible offers for one plan | One match commits; the other command returns a domain conflict; one selected offer exists |
| Selection is retry-safe | Database integration and HTTP component test | Send the same selection command many times with one idempotency key | Every response identifies the same match; one state transition and one outbox event exist |
| Expired offer cannot win | Database-time integration test | Select while the expiration worker evaluates the same offer | Either selection commits while still eligible or expiration wins; never both |
| Withdrawn offer cannot win | Concurrency integration test | Provider withdraws while organizer selects | Exactly one legal terminal outcome commits |
| Superseded request invalidates stale offers | Database integration test | Publish request version N+1 while selecting an offer for N | The old offer cannot create a match after supersession commits |
| Republish blocks stale offer submission | Concurrency integration test | Pause submission before its plan lock, then publish version N+1 | No submitted offer can commit against superseded version N |
| Suspension invalidates a racing recipient | Concurrency integration test | Publish a request while suspending a matched provider | A stale-version recipient cannot authorize access, notification, or offer submission |
| Suspension prevents stale selection or confirmation | Concurrency integration test | Suspend while selecting; reinstate before pending reconciliation | Old offer and match eligibility versions cannot become valid again |
| Confirmation deadline is final | Database-time integration test | Provider confirms while timeout job expires the pending match | Exactly one transition wins; a timed-out match is never revived |
| Invitation token is private and account-bound | PostgreSQL integration and HTTP security test | Present an unknown, wrong-account, expired, revoked, and consumed token | Every case returns the same 404 problem; no raw token is persisted or projected |
| One pending invitation exists per invitee | PostgreSQL concurrency integration test | Create two invitations for one group and invitee while an earlier pending invite expires | One valid pending invitation remains; an effectively expired one is marked `EXPIRED` under the group lock |
| Invitation acceptance creates one membership | PostgreSQL concurrency integration test | Accept one valid token concurrently and replay the accepted command | One membership is active, the invitation is consumed once, and exact replay returns the original result |
| Final organizer protection is deterministic | PostgreSQL concurrency integration test | Leave or remove the final organizer while another organizer transfer competes | The group lock yields one legal membership and role outcome; no group has zero active organizers |
| Requirement replacement preserves preference basis | PostgreSQL integration and HTTP concurrency test | Replace requirements while members submit preferences on a plan version | A write on an old basis returns 409; accepted older preferences remain readable and become stale after replacement |
| Plan cursor is stable | HTTP component test | List plans while ordinary updates occur and use malformed cursor input | Order remains `(created_at DESC, id DESC)`; updates do not move rows; malformed cursors return `INVALID_CURSOR` |
| M1 idempotent replay preserves the original response | PostgreSQL integration and HTTP component test | Replay each required command after its resource version advances, including invite creation | The original status, body, ETag, and Location return; token replay is reconstructed and no sensitive replay field is stored |
| Preference updates reject lost writes | HTTP concurrency test | Create twice with `If-None-Match: *`, then replace twice with one ETag | One create and one replacement win; stale competitors receive 412; one versioned preference remains |
| One current vote per member and offer | HTTP concurrency test | Create twice with `If-None-Match: *`, then replace twice with one ETag | One create and one replacement win; stale competitors receive 412; one current vote remains |
| Membership revocation is immediate for new writes | Concurrency integration test | Remove a member or transfer organizer authority while that account publishes, selects, or votes | Group-root locking gives a clear order: the protected write commits first, or the later authority check rejects it with no domain change |
| Closing a request terminalizes open offers | Database integration test | Close manually and through deadline branches while submissions race | A closed or cancelled request has no `SUBMITTED` offers; a reopened request preserves other eligible offers |
| Shared-plan ETags reject stale commands | HTTP concurrency test | Publish or select twice from the same plan ETag | At most one command advances the plan; stale competitors receive 412, except exact idempotent replay returns its stored result |
| M1 invitation acceptance and revocation serialize | `CollaborationRaceOutcomesIT.invitationAcceptanceAndRevocationHaveOneDurableOutcome` and `duplicateInvitationAcceptanceHandlesSameAndDifferentKeys` | Accept and revoke one invitation; accept one token with same and different idempotency keys | One terminal invitation state, one active membership at most, exact same-key replay, and one acceptance audit record |
| M1 organizer authority cannot disappear or leak | `CollaborationRaceOutcomesIT.concurrentOrganizerExitsKeepAnActiveOrganizer` and `organizerTransferAndRequirementEditRespectTheGroupLock` | Two organizers leave; transfer organizer authority while editing requirements | At least one active organizer remains; the edit commits under the lock or fails with no draft change |
| M1 requirement and preference versions reject lost writes | `CollaborationRaceOutcomesIT.requirementReplacementRacesPreferenceCreationWithoutPartialChildren`, `requirementReplacementRacesPreferenceReplacementWithoutPartialChildren`, `twoRequirementWritersWithOnePlanEtagLeaveOneCompleteDraft`, and `twoPreferenceWritersWithOnePreferenceEtagLeaveOneCurrentPreference` | Replace requirements while creating or replacing a preference; submit two requirement or preference writers with one ETag | One complete winning draft or preference snapshot remains; stale basis or ETag writers make no partial child rows |
| M1 membership removal orders protected writes | `CollaborationRaceOutcomesIT.membershipRemovalRacesPlanCreationAndPreferenceWrite` | Remove a member while that member creates a plan or writes a preference | The protected write commits before removal or is rejected after removal; audit rows and children match the winner |
| M1 cancellation terminalizes competing writes | `CollaborationRaceOutcomesIT.cancellationRacesRequirementAndPreferenceWritesWithoutPartialData` | Cancel while replacing requirements or a preference | One documented plan-version outcome commits; cancellation idempotency and audit state exist only when cancellation wins |
| Outbox is atomic with domain state | Failure integration test | Fail transaction before commit and after outbox insert | Neither domain state nor event commits on rollback; both commit together on success |
| Inbox deduplicates delivery | Messaging integration test | Deliver the same event ID repeatedly | One consumer effect is recorded; every later delivery is acknowledged safely |
| Poison messages reach DLQ | Messaging integration test | Force a consumer to fail beyond the configured receive count | Message becomes inspectable in the DLQ; no partial business effect is committed |

The billing rows below are billing extension gates, not request-first release
gates:

| Extension invariant | Primary proof | Required competing actions | Expected evidence |
| --- | --- | --- | --- |
| Free-plan offer limit is exact | Concurrency integration test | Submit more marketplace offers than the remaining allowance | Only the allowed count succeeds; direct invitations do not consume marketplace allowance |
| Cancellation preserves the current entitlement | PostgreSQL-time boundary test | Request cancellation before the state cutoff and delay reconciliation beyond it | Pro remains effective before the cutoff; Free is effective at and after it; stored state eventually becomes `CANCELED` once |
| Past-due grace is bounded | PostgreSQL-time integration test | Resolve access and process retry success on both sides of the fixed grace cutoff | Pro is effective only before `grace_ends_at`; recovery at or after it is a stale no-op |
| Subscription reconciliation is idempotent | Concurrency integration test | Run several reconcilers against the same overdue subscription | One terminal state, audit record, and outbox event exist; no timestamp is extended |
| Subscription downgrade preserves history | Integration test | Return to Free while offers and matches exist | New restricted actions are rejected; existing listings, offers, matches, usage, and audit data remain readable |

## How to test concurrent commands

Concurrency tests must use separate transactions and separate database connections. Calling the same service twice inside one test transaction does not reproduce the race.

For each race:

1. Arrange committed rows through public test fixtures.
2. Create a barrier that releases all workers at the same time.
3. Execute the real application command from each worker.
4. Collect success, conflict, and unexpected failure outcomes.
5. Open a new transaction after all workers finish.
6. Assert the complete durable state, constraints, audit records, and outbox rows.

The hot selection test should include more than two workers. For example, many duplicate requests for the same offer and several attempts to select different offers for the same plan can be released together. The assertion is still about the invariant, not thread timing: one winning match, one selection event, and predictable idempotent responses.

Run critical concurrency tests repeatedly in CI. A useful repeat count is chosen from observed runtime; it must not make the default feedback loop unusable.

## Transaction and failure tests

M2 covers PostgreSQL transaction rollback and outbox capture only. SQS,
consumer, retry, and email failure cases below begin in M4. Use fault injection
at explicit boundaries:

- before a transaction writes anything;
- after domain rows are changed but before commit;
- after an outbox row is written but before commit;
- after SQS accepts a message but before the relay marks the outbox row as published;
- after a consumer commits its effect but before it deletes the SQS message;
- while the database is temporarily unavailable;
- while the SQS endpoint is unavailable;
- while Mailpit or the future email provider is unavailable.

Expected behavior:

- database rollbacks leave no partial domain or outbox state;
- publisher ambiguity may create another delivery, never a lost committed event;
- consumer ambiguity is absorbed by inbox deduplication;
- retries use bounded exponential backoff with jitter;
- exhausted asynchronous work becomes operationally visible through the DLQ or a failed-work record;
- request threads do not wait for email delivery.

## PostgreSQL integration environment

The integration suite should:

- start a supported PostgreSQL image through Testcontainers;
- run the same Flyway migrations used by the application;
- fail if migration validation fails;
- avoid schema creation by Hibernate;
- truncate or recreate state between tests without relying on test order;
- use enough connections to reproduce concurrent transactions;
- retain SQL and lock diagnostics when a concurrency test times out.

Tests should assert database constraints by attempting the invalid write, not only by checking validation code.

## SQS and Floci integration environment (M4, planned)

Floci is used to exercise the AWS SDK v2 SQS adapter with a standard queue and DLQ. The suite should cover:

- queue creation or discovery;
- send, receive, delete, and visibility changes;
- long polling;
- redelivery when a message is not acknowledged;
- redrive to the DLQ;
- duplicate event delivery;
- consumer restart while messages are in flight.

The [official Floci Java Testcontainers guide](https://floci.io/floci/testcontainers/java/) documents direct AWS SDK v2 integration. Emulator tests prove the application protocol and local failure handling. They do not prove AWS IAM, quotas, encryption, multi-AZ behavior, service latency, or complete Amazon SQS parity. Before an AWS deployment, run a small compatibility suite against an isolated real SQS queue.

## API and security tests

At the HTTP boundary, verify:

- validation errors have stable machine-readable codes;
- unauthorized users receive no existence information about private groups or plans;
- only organizers can publish requests and select offers;
- only the owning provider can submit, withdraw, or confirm its offer;
- idempotency keys are scoped to the authenticated actor and command type;
- pagination has stable ordering and cannot skip or duplicate items during ordinary updates;
- provider projections exclude member identity, employer, private notes, and
  contact fields; deliberately publishable organizer text is not automatically
  inspected or redacted for PII;
- audit endpoints cannot be edited through normal APIs.

## Load testing with k6

k6 scenarios should model separate workloads rather than one unrealistic global test:

### Marketplace browsing

Read-heavy traffic over the provider request feed and group-visible offers.
Measure latency, database connection use, and query count. Add listing search to
this scenario only after the listing extension exists.

### Provider response burst

Many providers read matched requirements and submit offers near an offer deadline. Include both eligible and rejected submissions.

### Hot plan contention

Many clients vote and retry while two organizer devices attempt to select different offers. The goal is to prove the one-winner invariant under pressure.

### Asynchronous backlog

Pause or slow the consumer, produce events, then restore it. Measure outbox age, queue age, drain rate, retries, and duplicate absorption.

### Soak

Run mixed traffic long enough to reveal connection leaks, unbounded tables, scheduler drift, or growing memory.

Every script must record:

- source revision;
- application and dependency versions;
- hardware or runner details;
- dataset size;
- virtual-user and arrival-rate configuration;
- duration;
- thresholds;
- expected business conflicts separately from system failures.

Do not publish invented numbers. Store raw k6 output and a short interpretation only after a reproducible run. A conflict caused by two valid attempts to win the same plan is a business outcome, not an HTTP 500 and not necessarily a load-test failure.

## CI quality gates

A target CI pipeline should enforce:

1. compile and static checks;
2. unit tests;
3. Flyway validation and PostgreSQL integration tests;
4. Floci SQS integration tests from M4;
5. component smoke test;
6. build of the deployable container;
7. scheduled or manually triggered k6 tests.

Minimum milestone completion conditions:

- all correctness tests pass;
- no migration checksum drift;
- no disabled invariant test without a documented reason;
- no flaky-test retry used to hide a known race;
- changed workflows have corresponding tests and documentation updates.

Coverage percentage is supporting information, not the acceptance criterion. Critical state transitions and invariants must have explicit named tests even if line coverage is already high.

## Evidence to keep in the repository

When implementation begins, retain:

- named concurrency tests for every race in the matrix;
- migration files and constraint comments;
- k6 scripts and datasets;
- instructions for reproducing each suite;
- example failure diagnostics;
- benchmark reports tied to commits, if and only if the tests were actually run;
- a short explanation when emulator behavior differs from real AWS.

This evidence supports the system's correctness claims. Architecture prose
alone is not proof.
