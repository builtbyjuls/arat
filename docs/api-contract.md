# API Contract

## Status

This document defines the planned HTTP contract at resource and behavior
level. Release 1 is request-first; later-extension sections are labeled
explicitly. This is not an implemented OpenAPI specification.

The implementation milestone will create an executable OpenAPI document and
contract tests from these decisions.

## Principles

- Base path: /api/v1
- JSON request and response bodies
- UTC instants in RFC 3339 format
- Local plan dates and times include an IANA time zone
- UUID resource identifiers
- Decimal money encoded as strings with an ISO currency code; PostgreSQL stores
  integer minor units
- Cursor pagination for feeds and histories
- Authentication identity comes from the security principal
- Persistence records are never serialized directly
- Mutating retries use idempotency keys or version preconditions

## Local authentication

Release 1 uses a development-only identity provider or signed local token. The
principal identifies one account and may carry a platform-level
`PLATFORM_OPERATOR` role for restricted administrative paths.

`MEMBER`, `ORGANIZER`, `PROVIDER_STAFF`, and `PROVIDER_ADMIN` are not global
identity claims. The application resolves them from active membership records
for the specific group or provider organization named by the resource.

A caller-provided member or provider ID never replaces authorization from the
authenticated principal plus the relevant active membership.

Production identity-provider integration is out of scope.

## Concurrency headers

### Idempotency-Key

Required for commands that may be retried and create a durable result,
including:

- Create a group
- Accept an invitation
- Create a plan or finalize its requirements
- Publish a provider request
- Close or cancel a provider request
- Submit or withdraw an offer
- Select an offer
- Confirm, decline, complete, or cancel a match
- Start or change a simulated subscription
- Submit or decide provider verification and apply a provider restriction

The canonical uniqueness scope is authenticated actor, operation, and key:
`(actor_id, operation, idempotency_key)`. The operation name already identifies
the route semantics. Reusing a key
with a different request fingerprint returns a conflict.

The server stores the original status code and response for replay.

### ETag and If-Match

Mutable planning resources expose an ETag based on their version.

Organizer commands that change shared plan requirements require If-Match.
Each member preference has its own version, so preference edits do not consume
the shared plan ETag even though the transaction briefly locks the plan to
validate its current state.

For a resource that does not yet exist, the first `PUT` uses
`If-None-Match: *`. A successful create returns its first ETag. Later `PUT`
requests use `If-Match` with that ETag. Exactly one of those preconditions is
required for preference and vote writes.

The server compares a supplied version after acquiring the transaction's
required locks. A stale `If-Match`, or `If-None-Match: *` when the resource
already exists, returns HTTP 412. An exact retry of a completed idempotent
command returns its stored response before current-version validation; this
keeps a successful retry from failing merely because the original command
advanced the version.

## Error envelope

~~~json
{
  "type": "https://arat.example/problems/offer-not-selectable",
  "title": "Offer cannot be selected",
  "status": 409,
  "code": "OFFER_NOT_SELECTABLE",
  "detail": "The offer expired before it was selected.",
  "instance": "/api/v1/plans/plan-id/selection",
  "correlationId": "request-correlation-id",
  "violations": []
}
~~~

Validation errors include field-level violations. Internal exception names,
SQL text, provider-private data, and stack traces are never returned.

## Group APIs

### Create a group

~~~http
POST /api/v1/groups
Idempotency-Key: ...
~~~

Request fields:

- name
- optional description

The creator becomes the organizer.

### Read a group

~~~http
GET /api/v1/groups/{groupId}
~~~

Only active members can read group details.

### Create and revoke invite links

~~~http
POST /api/v1/groups/{groupId}/invites
DELETE /api/v1/groups/{groupId}/invites/{inviteId}
~~~

Invite tokens are returned only when created and are stored hashed.

### Join through an invite

~~~http
POST /api/v1/group-invites/{token}/accept
Idempotency-Key: ...
~~~

### Transfer organizer ownership

~~~http
POST /api/v1/groups/{groupId}/organizer-transfer
If-Match: "group-version"
~~~

### Leave or remove a member

~~~http
DELETE /api/v1/groups/{groupId}/members/me
DELETE /api/v1/groups/{groupId}/members/{accountId}
Idempotency-Key: ...
~~~

The final active organizer cannot leave or be removed. Removing another member
requires organizer authority.

## Plan APIs

### Create a plan

~~~http
POST /api/v1/groups/{groupId}/plans
Idempotency-Key: ...
~~~

Core fields:

- title
- activity category
- candidate date and time windows
- IANA time zone
- broad location preference
- initial headcount range
- optional budget range

### Read or list plans

~~~http
GET /api/v1/groups/{groupId}/plans?cursor=...
GET /api/v1/plans/{planId}
~~~

### Update organizer-owned requirements

~~~http
PATCH /api/v1/plans/{planId}
If-Match: "plan-version"
~~~

Material edits after publication create a new draft requirement version. They
do not rewrite the published snapshot answered by existing offers.

### Read or set a member preference

~~~http
GET /api/v1/plans/{planId}/members/me/preference
PUT /api/v1/plans/{planId}/members/me/preference
If-None-Match: *
~~~

The first `PUT` uses `If-None-Match: *`. Replacements use
`If-Match: "preference-version"`. Successful reads and writes return the
current preference ETag.

Fields may include:

- Available date and time windows
- Attendance state: INTERESTED, AVAILABLE, JOINING, NOT_JOINING
- Guest count
- Maximum personal budget
- Ranked activity or amenity preferences
- Private note visible only to group members

### Cancel a plan

~~~http
POST /api/v1/plans/{planId}/cancellation
Idempotency-Key: ...
If-Match: "plan-version"
~~~

Cancellation is terminal for the plan. Its current request becomes
`CANCELLED`, remaining `SUBMITTED` offers become `NOT_SELECTED`, and any
pending match becomes `CANCELLED` atomically. A new outing attempt uses a new
plan.

### Finalize requirements

~~~http
POST /api/v1/plans/{planId}/requirement-finalization
Idempotency-Key: ...
If-Match: "plan-version"
~~~

The response contains the calculated headcount range, compatible time windows,
budget range, and unresolved warnings. Finalization does not publish anything.

## Provider-request APIs

### Publish a requirement

~~~http
POST /api/v1/plans/{planId}/published-requests
Idempotency-Key: ...
If-Match: "plan-version"
~~~

The server creates an immutable provider-visible snapshot with:

- Version number
- Category
- Broad area or radius
- Date and time window
- Headcount range
- Budget range
- Must-have requirements
- Offer deadline

Private member details and exact workplace or home location are excluded.

### Read request status

~~~http
GET /api/v1/plans/{planId}/published-requests/current
~~~

### Close a request

~~~http
POST /api/v1/published-requests/{requestId}/closure
Idempotency-Key: ...
If-Match: "plan-version"
~~~

Closure is atomic: the request becomes `CLOSED`, every remaining `SUBMITTED`
offer for that request becomes `NOT_SELECTED`, and the plan returns to
`COLLABORATING` when no match has been confirmed. Manual closure is accepted
only while the request is `OPEN`; a pending selection is ended through the
match-cancellation endpoint.

### Provider request feed

~~~http
GET /api/v1/providers/{providerId}/request-feed?category=COURT&area=...&cursor=...
~~~

The server returns only requests that provider organization is authorized and
eligible to view. The authenticated account must have active staff membership
in `{providerId}`. Provider context is explicit because one account may work
for more than one provider organization. The feed does not expose competing
offers.

## Provider APIs

### Create or update provider profile

~~~http
POST /api/v1/providers
PATCH /api/v1/providers/{providerId}
If-Match: "provider-version"
~~~

## Listing APIs (listing extension)

### Create and manage listings

~~~http
POST /api/v1/providers/{providerId}/listings
PATCH /api/v1/listings/{listingId}
If-Match: "listing-version"
~~~

~~~http
POST /api/v1/listings/{listingId}/publication
POST /api/v1/listings/{listingId}/pause
POST /api/v1/listings/{listingId}/resume
POST /api/v1/listings/{listingId}/archive
Idempotency-Key: ...
~~~

Listing fields are category-specific but share:

- Title and description
- Service category
- Service area
- Capacity range
- Indicative price or package range
- Amenities
- Provider terms
- Publication status

Listing publication does not guarantee real-time availability.

### Browse listings

~~~http
GET /api/v1/listings?category=COURT&area=...&headcount=...&cursor=...
GET /api/v1/listings/{listingId}
~~~

### Request an offer from a listing

~~~http
POST /api/v1/plans/{planId}/listing-invitations
Idempotency-Key: ...
If-Match: "plan-version"
~~~

This publishes a new `DIRECT_INVITATION` request version targeted only to the
listing's provider. It supersedes any current request version for the plan; it
does not add a provider to an existing matched-pool audience.

## Offer APIs

### Submit an offer

~~~http
POST /api/v1/providers/{providerId}/published-requests/{requestId}/offers
Idempotency-Key: ...
~~~

Offer fields:

- Optional source listing identifier
- Optional `revisionOfOfferId` when replacing the provider's current submitted
  offer for the same request
- Exact proposed date and time
- Capacity
- Total price and currency
- Inclusions
- Conditions
- Cancellation summary
- Expiration instant

The path identifies the provider organization that owns the offer and, after
the billing extension, the usage counter to charge. The authenticated account
must have active staff membership in that organization, and the organization
must own an active recipient authorization for the request. The server never
guesses provider context from the caller's memberships.

The provider does not choose the confirmation deadline. Selection applies the
server policy: the Release 1 default is a 30-minute confirmation window, and
selection is rejected unless that full window ends before the outing starts.

When `revisionOfOfferId` is present, the referenced offer must be the same
provider's current `SUBMITTED` offer for this request. The transaction locks it,
marks it `SUPERSEDED`, and inserts the new sealed revision. Submitted terms are
never patched in place.

Before the billing extension, verified recipients submit without
metering. After that extension is enabled, a proactive marketplace offer
consumes one provider usage unit when the effective entitlement is finite. A
direct invitation response does not.

### Read offers for a plan

~~~http
GET /api/v1/plans/{planId}/offers?cursor=...
GET /api/v1/offers/{offerId}
~~~

Only group members and the offer's provider can see the full offer. A provider
cannot see another provider's offer.

### Withdraw an offer

~~~http
POST /api/v1/offers/{offerId}/withdrawal
Idempotency-Key: ...
~~~

Withdrawal is rejected after an offer has entered an active match.

### Vote on an offer

~~~http
GET /api/v1/plans/{planId}/offers/{offerId}/votes/me
PUT /api/v1/plans/{planId}/offers/{offerId}/votes/me
If-None-Match: *
~~~

Voting is advisory. One member has one current vote for each offer. A member
may replace that vote while voting remains open. The first vote uses
`If-None-Match: *`; a replacement uses `If-Match: "vote-version"`. Successful
reads and writes return the current vote ETag.

## Match APIs

### Select an offer

~~~http
POST /api/v1/plans/{planId}/selection
Idempotency-Key: ...
If-Match: "plan-version"
~~~

~~~json
{
  "offerId": "offer-id"
}
~~~

Success creates one AWAITING_PROVIDER_CONFIRMATION match containing an
immutable snapshot of the selected offer and request version.

Expected conflicts include:

- Offer expired
- Offer withdrawn
- Request superseded
- Plan already has an active match
- Caller is not the organizer

### Provider confirmation

~~~http
POST /api/v1/matches/{matchId}/confirmation
Idempotency-Key: ...
~~~

### Provider decline

~~~http
POST /api/v1/matches/{matchId}/decline
Idempotency-Key: ...
~~~

For confirmation and decline, the match identifies the provider organization.
The caller must have active staff membership for that exact organization; the
server does not choose among the caller's provider memberships.

After decline or timeout, the organizer may select another still-valid offer
or publish a new requirement version.

### Complete or cancel

~~~http
POST /api/v1/matches/{matchId}/completion
POST /api/v1/matches/{matchId}/cancellation
Idempotency-Key: ...
~~~

Completion is a coordination record, not proof of payment or attendance.

Cancelling an `AWAITING_PROVIDER_CONFIRMATION` match cancels only that pending
selection. The request reopens when its offer deadline remains in the future;
otherwise it closes and the plan returns to collaboration. Cancelling a
`CONFIRMED` match makes the plan terminal. The separate plan-cancellation
endpoint is always terminal regardless of current plan state.

## Simulated billing APIs

These extension endpoints are implemented only after the core request-first
release.

Business-facing endpoints:

~~~http
GET /api/v1/providers/{providerId}/subscription
GET /api/v1/providers/{providerId}/entitlements
GET /api/v1/providers/{providerId}/usage
POST /api/v1/providers/{providerId}/subscription
POST /api/v1/providers/{providerId}/subscription/cancellation
~~~

Local-only simulator endpoints are available only in local and test profiles:

~~~http
POST /internal/simulator/billing/events
~~~

These endpoints expose simulated plan and entitlement state only. They do not
create an invoice or accept a payment instrument.

Subscription and entitlement reads distinguish stored lifecycle from effective
access. They return `lifecycleState`, `cancelAtPeriodEnd`, `effectivePlan`,
`entitlementEndsAt`, PostgreSQL `evaluatedAt`, and `reconciliationPending`.
Clients authorize features from the effective plan, not from lifecycle state
alone. This remains correct when an expiration worker is late.

`POST .../subscription/cancellation` is idempotent. It sets
`cancelAtPeriodEnd` but leaves `TRIALING`, `ACTIVE`, or `PAST_DUE` unchanged.
Pro therefore remains effective until the existing trial, paid-period, or
past-due grace cutoff. The endpoint never provides immediate cancellation.

The local event endpoint supports deterministic activation, renewal success,
renewal failure, retry success, and reconciliation scenarios. It does not
change a process-wide clock. Tests and local scenarios create period bounds
relative to PostgreSQL time, and every entitlement decision uses
`transaction_timestamp()`. Renewal failure is accepted only while the current
paid period is effective; after its cutoff, it is a stale no-op rather than a
way to create a grace window late.

## Operations APIs

Planned operations endpoints:

- Spring Boot health and readiness
- Prometheus metrics
- Outbox and inbox backlog summaries for authorized operators
- Redrive command for a failed notification after operator review

Provider verification commands:

~~~http
POST /api/v1/providers/{providerId}/verification-submissions
POST /api/v1/operations/providers/{providerId}/verification-decisions
POST /api/v1/operations/providers/{providerId}/suspension
POST /api/v1/operations/providers/{providerId}/restoration
Idempotency-Key: ...
~~~

The submission contains configured evidence references, not uploaded identity
documents. Decision, suspension, and restoration commands require the
platform-level operator role and append an audit record.

Administrative endpoints require a separate operator role and are not part of
the public API surface.

## Pagination

List endpoints return:

~~~json
{
  "items": [],
  "nextCursor": null
}
~~~

Cursors are opaque. Clients must not construct or interpret them.

## HTTP outcome guide

| Status | Meaning |
| --- | --- |
| 200 | Read, replay, or successful state command |
| 201 | New resource created |
| 202 | Asynchronous work accepted |
| 204 | Successful command with no body |
| 400 | Malformed request |
| 401 | Authentication required |
| 403 | Authenticated but not authorized |
| 404 | Resource not visible or not found |
| 409 | Valid request conflicts with current domain state |
| 412 | Version or first-write precondition failed |
| 422 | Well-formed input violates validation rules |
| 429 | Rate or subscription usage limit reached |

## Contract-test expectations

- Every documented problem code has an API test.
- Unauthorized users cannot infer private resource existence.
- Idempotent replay returns the original logical result.
- Reusing an idempotency key with different input returns conflict.
- Stale `If-Match` and conflicting `If-None-Match: *` requests return 412.
- Provider feeds never expose member identities or competing offers.
- An account with staff memberships in two providers acts only for the
  provider organization named by the route or owned by the target resource.
- Local simulator endpoints are absent outside allowed profiles.
- OpenAPI examples remain valid against implemented schemas.
