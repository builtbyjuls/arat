# ADR-0003: Use Versioned Requests and Explicit Provider Confirmation

- Status: Accepted
- Date: 2026-09-17

## Context

Groups refine plans over time. Headcount, schedule, area, budget, and requirements may change after providers have seen a request or submitted an offer.

Providers in the MVP do not expose a reliable real-time inventory API to Arat?. They may manage availability through another calendar, messages, phone calls, or walk-in transactions. Arat? therefore cannot honestly guarantee that an offer remains available at the instant an organizer selects it.

The design must answer two questions clearly:

1. Which exact group requirements did a provider answer?
2. When does an organizer's selection become a confirmed agreement?

## Options considered

### Option A: Mutable request with offers attached to the latest plan

Edit one published request record in place and display existing offers against the changed plan.

Benefits:

- Simple user interface.
- Fewer stored records.

Costs:

- A provider's offer can appear to cover terms they never saw.
- Auditing and dispute explanation become unreliable.
- Cache, notification, and concurrency behavior becomes ambiguous.
- Material edits may invalidate price, capacity, or schedule without explicit re-quotation.

### Option B: Treat organizer selection as an instant booking

Assume every provider offer reserves inventory and mark a booking confirmed as soon as the organizer selects it.

Benefits:

- Fast and familiar checkout experience.
- Fewer visible workflow states.

Costs:

- Makes a guarantee Arat? cannot enforce in the MVP.
- Creates false double-booking protection without an authoritative provider calendar.
- Converts provider operational delays into broken confirmed bookings.

### Option C: Immutable request versions and explicit provider confirmation

Publish an immutable provider-visible snapshot. Offers answer one version. Organizer selection creates a pending match that the provider must confirm before a fixed deadline.

Benefits:

- Terms are auditable.
- Material changes force an explicit new quote.
- Product language matches the actual availability model.
- Selection and timeout races have clear state transitions.

Costs:

- Adds request versions and pending-match states.
- Confirmation introduces delay and possible disappointment.
- Providers must respond promptly.
- The interface must explain that selection is not yet confirmation.

## Decision

Arat? will use immutable, versioned published requests and explicit provider confirmation.

### Published requests (M2, implemented)

- Planning finalizes one active window, deadline, and provider-publishable terms
  from the locked plan version. This immutable resource does not change plan
  state or version. Preferences remain advisory counts/warnings, never automatic
  headcount, budget, or schedule changes.
- Publishing accepts only a same-plan finalization based on the locked current
  plan version and a still-future database deadline. The offer deadline must be
  strictly before the chosen start.
- Publishing captures an allowlist copy of that finalization, excluding private
  group, plan-title, identity, preference, attendance, employer, and contact
  fields. Provider-safe notes, must-haves, and string attributes are deliberately
  publishable text without automatic PII detection or redaction.
- `(plan_id, version)` is unique and version numbers increase monotonically per plan.
- At most one version is current.
- Requirements and preferences remain editable privately in OPEN_FOR_OFFERS;
  N stays current until direct publication of N+1 atomically supersedes it.
  There is no close-first gap and no in-place audience expansion.
- Snapshot fields and ordered children are immutable in PostgreSQL. Lifecycle
  changes use guarded commands; M2 creates OPEN, SUPERSEDED, CLOSED, CANCELLED.
- Closure and cancellation clear the plan's same-plan current pointer and retain
  history. In M2 the pointer exists only in OPEN_FOR_OFFERS.
- Old versions remain readable only under current authorization. Provider
  access requires active staff, VERIFIED, ACTIVE recipient, and captured/current
  eligibility equality; suspension followed by restoration never revives access.
- Finalization/publication replay reconstructs immutable resources using compact
  references, original status/headers, and small fixed-shape metadata only. It
  supports maximum valid multibyte content and sensitive-looking valid attribute
  keys without changing the generic 16 KB limit or sensitive-key guard.
  Publication replay returns the original OPEN representation even after a
  terminal lifecycle change.
- Offers against a superseded version cannot be selected.

### Offers (M3, planned)

- Every offer references exactly one published request version.
- Price, schedule, capacity, inclusions, conditions, and expiration are sealed at submission.
- Providers cannot see competing offer prices or terms.
- A provider changes terms by submitting a new immutable offer revision that supersedes the prior submitted revision.
- Listing-first and request-first discovery both produce this same offer model.

### Selection and match (M3, planned; notification delivery in M4)

- Group votes are advisory.
- Only the organizer selects an offer in the MVP.
- Selection changes the offer to `SELECTED`, changes the request to `SELECTION_PENDING`, and creates a match in `AWAITING_PROVIDER_CONFIRMATION`.
- The provider must explicitly confirm before the database-controlled deadline.
- Confirmation changes the match to `CONFIRMED`.
- Decline, timeout, and cancellation are retained as terminal attempts.
- A partial unique index permits at most one active match per plan, where awaiting confirmation and confirmed are active.
- At most one match may ever reach `CONFIRMED` for a plan.
- When a pending attempt is declined or times out, the request and plan can return to open state if their deadlines permit. The selected offer remains terminal, so the provider must submit a new offer before it can be considered again.

API and interface language must say `offer`, `selected`, `awaiting provider confirmation`, and `confirmed match`. It must not call an unconfirmed selection a guaranteed booking.

## Consequences

### Positive

- A reviewer can reconstruct exactly what both parties agreed to.
- Requirement edits cannot silently change an offer's meaning.
- The product is honest about the absence of integrated provider inventory.
- Concurrency tests can target clear selection, withdrawal, expiration, confirmation, and timeout transitions.
- A future provider calendar integration can strengthen confirmation without changing the group planning model.

### Negative

- More states and terminal history increase implementation and interface complexity.
- Groups may wait for a provider who later declines.
- Republishing can require several providers to quote again.
- Notification delays can reduce the time available for confirmation if deadlines are poorly chosen.

### Mitigations

- Display the confirmation deadline and current state prominently.
- Notify provider staff asynchronously immediately after selection.
- Keep other offers visible but non-selectable while one active match exists.
- Reopen the plan automatically after a decline or timeout when policy allows.
- Record response-time metrics without promising unmeasured service levels.
- Explain why an offer became superseded or ineligible and allow a provider to base a new revision on its prior terms.

## Revisit criteria

The confirmation step may be shortened or automated for a provider only when Arat? has an authoritative integration that can atomically reserve that provider's inventory and the provider accepts the operational contract.

Possible evidence includes:

- A provider calendar API with reliable availability and hold semantics
- A signed integration agreement defining ownership of conflicts
- Measured confirmation performance showing a different workflow is needed
- Real payment processing with defined cancellation and refund behavior

Until then, removing confirmation would make a guarantee the platform cannot prove.
