# API Contract

## Status

This document defines the HTTP contract at resource and behavior level. The
Milestone 1 collaboration routes and problem-envelope codes are implemented
and exposed as executable OpenAPI at `/v3/api-docs`; their component test is
`MilestoneOneJourneyIT`. Release 1 is request-first; later-extension sections
remain planned and are labeled explicitly.

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

The `local` profile provides three local-only development tokens. They identify
the fixed accounts below, each with no platform roles:

| Token | Account | Display name |
| --- | --- | --- |
| `arat-local-owner-token` | `10000000-0000-4000-8000-000000000001` | Ari Organizer |
| `arat-local-member-token` | `10000000-0000-4000-8000-000000000002` | Bea Member |
| `arat-local-outsider-token` | `10000000-0000-4000-8000-000000000003` | Cruz Outsider |

The Bearer scheme is case-insensitive, while opaque token values are
case-sensitive. These fake account rows load only under `local`.
`GET /api/v1/dev/whoami` is available only under `local` and returns only that
actor UUID as `actorId` to prove the current-actor boundary.

Every `/api/**` path requires an authenticated principal. Missing, malformed,
or unknown credentials return a correlated `401 AUTHENTICATION_REQUIRED`
problem with `WWW-Authenticate: Bearer`; authenticated authorization failures
return `403 ACCESS_DENIED`. Production has no identity adapter and therefore
fails closed. Startup rejects any `prod` profile combined with `local` or
`compose`; fake identity components and the development probe also explicitly
exclude `prod`.

The principal may later carry a platform-level `PLATFORM_OPERATOR` role for
restricted administrative paths.

`MEMBER` and `ORGANIZER`, and provider staff roles `ADMIN` and `STAFF`, are
not global identity claims. The application resolves them from active
membership records for the specific group or provider organization named by
the resource.

A caller-provided member or provider ID never replaces authorization from the
authenticated principal plus the relevant active membership.

Production identity-provider integration is out of scope.

## Milestone 1 collaboration contract

This section is the authoritative implemented contract for Phase 1 private
group collaboration. Requirement finalization and provider publication begin
in Phase 2; Phase 1 creates and edits a private collaboration draft only.

### Headers, retries, and errors

Group, plan, and preference ETags are quoted integer versions, such as `"7"`.
A required missing `If-Match` or `If-None-Match` returns `428
PRECONDITION_REQUIRED`; a malformed precondition returns `400
INVALID_PRECONDITION`; and a stale or failed valid precondition returns `412
PRECONDITION_FAILED`. The first preference `PUT` requires `If-None-Match: *`.
Later preference replacements require that preference's `If-Match`. Requirement
replacement uses the plan's `If-Match`.

`Idempotency-Key` is required for group creation, invitation creation and
revocation, invitation acceptance, self-leave, member removal, organizer
transfer, plan creation, and plan cancellation. Its scope is authenticated
actor, operation, and key: `(actor_id, operation, idempotency_key)`.
Requirement replacement and preference writes use only their version
preconditions. A fingerprint hashes the canonical validated command; invite
acceptance fingerprints the token digest, never the raw token. Records retain
status and bounded replay state for seven days. Replay state is at most 16 KB,
normally contains the response body, and stores only allowlisted `ETag` and
`Location` response headers with a combined 2 KB limit. An invitation token is
reconstructed for an exact invite-create replay and is never stored in replay
state.

An exact completed replay returns the original status, representation, ETag,
and Location before current ETag validation. Reusing a key with different
content returns `409 IDEMPOTENCY_KEY_REUSED` before mutation. Normal
authentication failure remains 401 and malformed request syntax remains 400.

The implemented M1 problem codes are:

`PRIVATE_RESOURCE_NOT_FOUND`, `FORBIDDEN_ROLE`, `FINAL_ORGANIZER_REQUIRED`,
`INVITATION_UNAVAILABLE`, `ALREADY_MEMBER`, `PRECONDITION_REQUIRED`,
`INVALID_PRECONDITION`, `PRECONDITION_FAILED`, `IDEMPOTENCY_KEY_REUSED`,
`INVALID_CURSOR`, `INVALID_PLAN_STATE`, `INVITATION_ALREADY_PENDING`,
`PREFERENCE_NOT_FOUND`, `REQUIREMENT_VERSION_CHANGED`, `ALREADY_ORGANIZER`,
and `VALIDATION_FAILED`.

### M1 request and representation rules

M1 activity categories are exactly `COURT`, `KTV`, and `GROUP_DINING`. Group
names are 1-80 characters and descriptions are 0-500. Plan titles are 1-120
characters. A plan has a valid IANA time zone and 1-10 candidate windows. Each
window uses RFC 3339 instants and has `startAt` before `endAt`.

Requirement requests use this shape:

~~~json
{
  "title": "Friday badminton",
  "category": "COURT",
  "timeZone": "Asia/Manila",
  "candidateWindows": [{
    "id": "optional-retained-uuid",
    "startAt": "2027-01-09T09:00:00Z",
    "endAt": "2027-01-09T11:00:00Z"
  }],
  "area": {"code": "BGC", "radiusKm": 5},
  "headcount": {"minimum": 4, "maximum": 10},
  "budget": {
    "currency": "PHP",
    "minimumAmount": "0.00",
    "maximumAmount": "2500.00"
  },
  "mustHaves": ["parking", "shower"],
  "providerSafeNotes": "Indoor court preferred.",
  "categoryAttributes": {"hasParking": true, "courtCount": 2}
}
~~~

Candidate windows are `{id?, startAt, endAt}`. Creation omits `id`; responses
assign a UUID. A replacement retains a window by sending that UUID. An unknown
or cross-plan UUID is invalid. Removed windows are retired, rather than
physically deleted, so old preferences remain explainable. `area.code` is 1-64
characters and `radiusKm` is 1-100. Headcount is 1-100 and its minimum cannot
exceed its maximum. `mustHaves` is an ordered unique array of at most 20
strings, each 1-120 characters. `providerSafeNotes` is optional and at most
1000 characters.

Budget and personal budget amounts are public decimal strings, not minor units.
They use plain decimal notation with exactly two fraction digits, are in PHP,
and range from `0.00` through `1000000.00`; the boundary converts them exactly
to integer minor units from 0 through 100000000. A budget range has minimum no
greater than maximum.
At most 20 category attributes are allowed. Their lower-camel-case keys are
1-40 ASCII letters or digits and begin with a letter. Values are only Boolean,
an integer from 0 through 1000000, or a 1-120 character string. Null, decimal,
array, and nested-object values are invalid.

A preference request contains a positive integer `basisPlanVersion`, attendance
(`INTERESTED`, `AVAILABLE`, `JOINING`, or `NOT_JOINING`), `guestCount` from
0-20, selected window IDs that exist on the current plan draft, optional
personal budget, ranked
preferences, and an optional private note. `guestCount` counts guests in
addition to the member and is zero for `NOT_JOINING`. Ranked preferences are an
ordered unique array of trimmed 1-80 character strings, with at most 10 items;
array order is rank. The private note is at most 1000 characters. A personal
PHP budget uses the same absolute money bounds and need not fit the plan budget.

~~~json
{
  "basisPlanVersion": 7,
  "attendance": "JOINING",
  "guestCount": 1,
  "selectedWindowIds": ["candidate-window-uuid"],
  "personalBudget": {"currency": "PHP", "amount": "500.00"},
  "rankedPreferences": ["indoor court", "parking"],
  "privateNote": "I can bring a shuttlecock."
}
~~~

Group detail projects group fields and active member IDs, display names, and
roles. Plan lists are minimal summaries. Plan detail includes its requirement
draft but not preferences, so its ETag tags that representation. The separate
member-only plan-preferences collection includes each preference's basis version
and whether it remains current. Own-preference reads and writes use the
preference ETag. Tokens, invitation internals, idempotency records, and audit
internals are never projected.

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

Generic HTTP boundary failures use the codes `MALFORMED_REQUEST`,
`UNSUPPORTED_MEDIA_TYPE`, `METHOD_NOT_ALLOWED`, `ROUTE_NOT_FOUND`,
`VALIDATION_FAILED`, `NOT_ACCEPTABLE`, and `INTERNAL_ERROR`. Their `detail` and validation
messages are safe static text. Validation violations are objects with `field`
and `message`, sorted by field and then message.

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

The create body accepts `inviteeAccountId` and optional `expiryHours`. These are
secret, account-bound links for known local accounts. Expiry defaults to 72
hours and cannot exceed 168 hours. An invitation grants `MEMBER`, never
organizer. An active member cannot be invited; a former member can accept a new
invitation and reactivate as `MEMBER`. At most one pending invitation exists for
one group and invitee. Creation expires an effectively expired pending invite
while holding the group lock; a still-valid pending invite is a conflict.

The raw token is returned only on create. Its detailed derivation and storage
contract is defined in the [Domain and Data Model](domain-model.md#group_membership).

### Join through an invite

~~~http
POST /api/v1/group-invites/{token}/accept
Idempotency-Key: ...
~~~

### Transfer organizer ownership

~~~http
POST /api/v1/groups/{groupId}/organizer-transfer
If-Match: "group-version"
Idempotency-Key: ...
~~~

Multiple active organizers are allowed. Transfer promotes an active member and
demotes only the caller; other organizers do not change.

Success returns the new group ETag and this bounded representation:

~~~json
{
  "groupId": "group-uuid",
  "previousOrganizerAccountId": "caller-account-uuid",
  "organizerAccountId": "target-account-uuid",
  "groupVersion": 8
}
~~~

### Leave or remove a member

~~~http
DELETE /api/v1/groups/{groupId}/members/me
DELETE /api/v1/groups/{groupId}/members/{accountId}
Idempotency-Key: ...
~~~

The final active organizer cannot leave or be removed. Removing another member
requires organizer authority. Group version advances once when active
membership or roles change, but not when an invitation is created, expires, or
is revoked.

## Plan APIs

### Create a plan

~~~http
POST /api/v1/groups/{groupId}/plans
Idempotency-Key: ...
~~~

Any active member may create a plan. Public creation starts directly in
`COLLABORATING`; `DRAFT` is reserved and not exposed by M1 creation. The create
body uses the requirement shape above.

### Read or list plans

~~~http
GET /api/v1/groups/{groupId}/plans?cursor=...
GET /api/v1/plans/{planId}
~~~

### Replace organizer-owned requirements

~~~http
PUT /api/v1/plans/{planId}/requirements
If-Match: "plan-version"
~~~

This is full replacement. Every replacement advances the plan version and makes
older preferences stale without deleting or rewriting member input.

### Cancel a collaborative plan

~~~http
POST /api/v1/plans/{planId}/cancellation
Idempotency-Key: ...
If-Match: "plan-version"
~~~

Only an active organizer can cancel a plan. In M1, cancellation is terminal
and is accepted only from `COLLABORATING`. It advances the plan version while
preserving the private requirement draft and preferences for authorized read
history. An exact completed replay returns its original response and ETag
before checking the now-current plan version. A new command with a stale ETag
returns `412`; a new command with the current cancelled-plan ETag returns
`409 INVALID_PLAN_STATE`.

Later milestones extend plan cancellation to published requests, offers, and
matches. Those records do not exist in M1, so this endpoint does not create
provider cascades or notification delivery.

### Read or set a member preference

~~~http
GET /api/v1/plans/{planId}/members/me/preference
PUT /api/v1/plans/{planId}/members/me/preference
If-None-Match: *
~~~

The first `PUT` uses `If-None-Match: *`. Replacements use
`If-Match: "preference-version"`. Successful reads and writes return the
current preference ETag. Under the plan lock, `basisPlanVersion` must equal the
current plan version or the write returns `409 REQUIREMENT_VERSION_CHANGED`; an
accepted write stores that supplied basis.

Fields may include:

- Available date and time windows
- Attendance state: INTERESTED, AVAILABLE, JOINING, NOT_JOINING
- Guest count
- Maximum personal budget
- Ranked activity or amenity preferences
- Private note visible only to group members

~~~http
GET /api/v1/plans/{planId}/preferences
~~~

This collection is visible only to active group members and has no collection
ETag.

### M1 command outcomes

| Command | Success | Visible authorization or state errors |
| --- | --- | --- |
| Create group | 201 with ETag and Location | 422 validation; 409 idempotency reuse |
| Read group | 200 with ETag | missing or inactive access: 404 private resource |
| Create invite | 201 with Location and token | outsider: 404; active non-organizer: 403; unknown invitee or invalid expiry: 422; active invitee: 409 already member; pending invite: 409 invitation already pending |
| Revoke invite | 204 | outsider: 404; active non-organizer: 403; missing or non-pending invite: 404 invitation unavailable |
| Accept invite | 200 with new group ETag | every unavailable-token case: 404 invitation unavailable |
| Leave group | 204 with new group ETag | inactive caller: 404; final organizer: 409 |
| Remove member | 204 with new group ETag | outsider: 404; active non-organizer: 403; missing or inactive target: 404 private resource; self target: 422; final organizer: 409 |
| Transfer organizer | 200 with new group ETag | outsider: 404; active non-organizer: 403; missing or inactive target: 404 private resource; self target: 422; organizer target: 409 already organizer; precondition errors: 428, 400, or 412 |
| Create plan | 201 with ETag and Location | inactive access: 404; validation: 422 |
| Read or list plans | 200; detail has ETag | inactive or wrong-group access: 404; bad cursor: 400 |
| Replace requirements | 200 with new plan ETag | outsider: 404; active non-organizer: 403; cancelled plan: 409; precondition errors: 428, 400, or 412 |
| Read own preference | 200 with preference ETag | outsider: 404; no preference on visible plan: 404 preference not found |
| List plan preferences | 200 without ETag | outsider: 404 |
| Create preference | 201 with ETag and Location | outsider: 404; cancelled plan: 409; stale basis: 409; wrong resource precondition: 428, 400, or 412 |
| Replace preference | 200 with new ETag | outsider: 404; absent preference: 404 preference not found; cancelled plan: 409; stale basis: 409; wrong resource precondition: 428, 400, or 412 |
| Cancel plan | 200 with new plan ETag | outsider: 404; active non-organizer: 403; stale new command: 412; already cancelled with its current ETag under a new key: 409; other precondition errors: 428 or 400 |

Plan lists order by `(created_at DESC, id DESC)`, default to 20 items, allow at
most 100, and use an opaque versioned Base64url cursor. Updates never change
that list order. A malformed cursor returns `400 INVALID_CURSOR`.

For cancellation that is not an exact completed replay, the locked plan's ETag
is checked before plan state. Therefore a pre-cancellation ETag returns 412;
a new key with the current cancelled-plan ETag reaches state validation and
returns 409.

## Milestone 2 provider-request contract (planned)

M2 ends at immutable request publication and authorized access. It captures
notification intent in PostgreSQL but adds no relay, AWS SDK, SQS, Floci,
inbox, SMTP, email rendering, delivery retries, or DLQ behavior. Offers, votes,
selection, provider confirmation, and matches begin in M3; delivery begins in
M4. Listings, direct invitations, billing, advanced trust tooling, geospatial
search, and cloud deployment remain deferred.

### Command headers and replay

Every M2 command that creates a durable transition requires `Idempotency-Key`:
provider creation, verification submission and decision, suspension,
restoration, finalization, publication, closure, and plan cancellation.
Full replacements use `If-Match` without an additional command key unless they
also create a separate resource. Provider profile replacement uses the provider
ETag; finalization, publication, closure, and cancellation use the plan ETag.
ETags and error handling retain the quoted-integer M1 contract.

Authentication and the idempotency claim precede group, plan, and request locks.
Exact completed replay returns the original status, representation, ETag, and
Location before current ETag and state validation. The canonical fingerprint
includes route identifiers, validated body, and any supplied resource version.
Changing finalization ID, plan ID, or supplied plan version under the same
publication key returns `409 IDEMPOTENCY_KEY_REUSED`.

Finalization and publication store their immutable resource ID in the existing
idempotency resource reference, the original status and allowlisted headers,
and only small fixed-shape metadata when reconstruction needs it. They never
store their full response JSON in `ReplayState`. Exact replay reloads the
immutable finalization or request snapshot and reconstructs the original
representation. Publication replay returns its original `OPEN` result even
when current lifecycle metadata is `SUPERSEDED`, `CLOSED`, or `CANCELLED`.

M1's maximum valid multibyte strings can exceed the generic 16 KB replay bound.
Valid attribute keys may contain `token`, `secret`, `authorization`, `password`,
or `cookie`. Both kinds of input must finalize, publish, and replay normally.
Do not raise that bound, weaken sensitive-key detection, or special-case
arbitrary category-attribute keys in the generic idempotency component.

### Provider identity and profile

~~~http
POST /api/v1/providers
Idempotency-Key: ...
~~~

~~~json
{
  "displayName": "BGC Courts",
  "supportedCategories": ["COURT"],
  "serviceAreaCodes": ["BGC"]
}
~~~

Creation atomically creates one provider in `UNVERIFIED` and one `ACTIVE`
`ADMIN` staff membership for the authenticated creator. Display name is trimmed
and 1-120 characters. Categories are a unique set of 1-10 M1 values (`COURT`,
`KTV`, `GROUP_DINING`; currently only three distinct values exist). Area codes
are a unique set of 1-20 trimmed strings of 1-64 characters.

~~~http
GET /api/v1/providers/{providerId}
PUT /api/v1/providers/{providerId}/profile
If-Match: "provider-version"
~~~

Active `ADMIN` or `STAFF` members may read their provider. `ADMIN` replaces the
profile using the create body shape; category and area collections are full
replacements. The provider version advances, but eligibility version does not.
Edits affect future matching and never rewrite an existing recipient grant.
Provider context is always explicit in the route. A global platform role never
implies provider membership. M2 adds no staff invitation or removal endpoints;
tests may insert staff rows as fixtures.

Service-area codes are configured opaque identifiers matched by exact value.
The request radius is provider-visible context, not a distance calculation.

### Verification and eligibility

Only `VERIFIED` providers are eligible. An active provider `ADMIN` may submit
from `UNVERIFIED` or `REJECTED`. Submission carries 1-10 unique printable ASCII
opaque evidence references, each 1-256 characters. Evidence is referenced, not
uploaded, and is never returned to request recipients.

`PLATFORM_OPERATOR` may accept or reject `PENDING`, suspend `VERIFIED`, and
restore `SUSPENDED` to `VERIFIED`. Decision notes are optional and at most 500
characters; suspension requires a trimmed reason of 1-500 characters. The
[operations routes](#operations-apis) use an idempotency key for each command.
Every actual verification-state transition increments provider version and
monotonic eligibility version exactly once. Exact replay increments neither.
Restoration never restores a prior eligibility version.

### Finalize requirements

~~~http
POST /api/v1/plans/{planId}/requirement-finalization
Idempotency-Key: ...
If-Match: "plan-version"
~~~

~~~json
{
  "candidateWindowId": "active-candidate-window-uuid",
  "offerDeadline": "2027-01-08T10:00:00Z"
}
~~~

The organizer chooses one active candidate window. Under the plan lock,
finalization copies its values and all provider-publishable requirement fields
into an immutable resource with the current plan version as its basis.
It is allowed in `COLLABORATING` and `OPEN_FOR_OFFERS`, does not publish or
change plan state, and does not increment plan version. PostgreSQL decision
time requires a future offer deadline strictly before the selected start.

The response identifies the finalization and basis plan version, copied terms,
and bounded aggregate counts of current and stale submitted preferences, with
warnings such as no current preference input or stale input present.
Preferences are advisory; finalization never silently changes organizer
headcount, budget, schedule, or other terms. A later requirement replacement
advances plan version and makes the finalization stale.

### Edit while a request is open

M2 allows requirement and preference writes in both `COLLABORATING` and
`OPEN_FOR_OFFERS`, using their existing M1 authority and version preconditions.
They affect private draft and preference state only. Editing never mutates or
closes current request N. The organizer can finalize and publish N+1 directly;
N remains current until that replacement transaction commits. No close-first
gap is required.

### Publish a requirement

~~~http
POST /api/v1/plans/{planId}/published-requests
Idempotency-Key: ...
If-Match: "plan-version"
~~~

~~~json
{"finalizationId": "finalization-uuid"}
~~~

Publication requires a same-plan finalization whose basis version equals the
locked current plan version and whose offer deadline remains in the future by
PostgreSQL decision time. It allocates the next monotonically increasing request
version and publishes `MATCHED_POOL`. Matching selects distinct `VERIFIED`
provider IDs whose supported categories contain the request category and whose
service-area codes contain the exact area code, ordered by provider UUID
ascending, with the eligibility version observed by the query. Deduplication
precedes the cap: default 100, externally configurable from 1 through 500.
Zero candidates or more than the configured cap rejects the whole publication;
no domain state changes and no truncated audience is published.

The provider-visible representation uses an explicit allowlist:

- Request ID, request version, publication time, lifecycle state, and effective
  actionable flag
- Category, time zone, area code and radius
- Chosen start and end instants, headcount range, optional PHP budget range
- Ordered must-haves, category attributes, optional `providerSafeNotes`, and
  offer deadline

It excludes group ID and name, plan title, creator and member identities,
preferences, attendance, private notes, employer, and direct contact fields.
`providerSafeNotes`, must-haves, and string category attributes are deliberately
publishable organizer-authored text. The system does not promise automatic PII
detection or redaction of that text. M1 field bounds still apply; no private
entity is serialized wholesale.

The snapshot and ordered child content are immutable in PostgreSQL. Guarded
commands alone change lifecycle metadata. One plan has at most one current
`OPEN` or (from M3) `SELECTION_PENDING` request; M2 creates only `OPEN`,
`SUPERSEDED`, `CLOSED`, and `CANCELLED`. In M2 the plan holds a same-plan current
request reference only while `OPEN_FOR_OFFERS`; closure or cancellation clears
it and retains history.

Request, plan transition, fixed `MATCH_RULE` recipients with captured eligibility
versions, audit, idempotency completion, and all outbox rows commit together.
Replacement first marks N `SUPERSEDED` and non-actionable, then installs N+1
and its new fixed audience in the same transaction. Audiences never expand in
place. Per-recipient publication and terminal events contain no private fields;
see the [outbox contract](consistency-and-concurrency.md#9-outbox-and-consumer-idempotency).

### Group reads and closure

~~~http
GET /api/v1/plans/{planId}/published-requests/current
GET /api/v1/plans/{planId}/published-requests?cursor=...&limit=...
POST /api/v1/published-requests/{requestId}/closure
Idempotency-Key: ...
If-Match: "plan-version"
~~~

Active group members may read current requests and bounded history. Outsiders
receive `404 PRIVATE_RESOURCE_NOT_FOUND`. History uses opaque versioned cursors,
default limit 20 and maximum 100, in descending request-version order for the
named plan. Manual closure requires organizer authority and an `OPEN` request;
it marks it `CLOSED`, clears the current pointer, returns the plan to
`COLLABORATING`, advances plan version, and preserves history atomically.

M2 extends the existing plan-cancellation route to `OPEN_FOR_OFFERS`, marking
its current request `CANCELLED` and clearing the pointer in the same transaction.
Marketplace owns this request-aware route transaction and delegates plan and
request state changes to Planning, avoiding a Planning-to-Marketplace cycle.
Offer and match cascades begin in M3.

### Provider request detail and feed

~~~http
GET /api/v1/providers/{providerId}/published-requests/{requestId}
GET /api/v1/providers/{providerId}/request-feed?cursor=...&limit=...
~~~

Access requires active staff membership in the explicit provider context,
current provider `VERIFIED` state, an `ACTIVE` recipient, and equality between
stored and current eligibility versions. Cross-provider, non-recipient, revoked,
unverified, suspended, or stale-eligibility reads return the same private-resource
not-found response; they do not disclose resource existence. Restoration never
revives an old grant. Authorized detail may return a terminal historical snapshot.

Feed order is recipient `created_at DESC`, then request ID descending. Cursors
are opaque, versioned, and bound to provider ID. Limit defaults to 20 and may
not exceed 100. M2 has no category or area filters: recipients already matched
those criteria, and cross-module filtering would corrupt recipient-owned
pagination. Authorization is applied before the cursor page is limited, never
by filtering a recipient-owned page afterward.

The feed may include authorized terminal history and returns lifecycle state
plus an effective actionable flag. It is true only for a current `OPEN` request
before its offer deadline by database time with valid access. M2 has no expiry
worker; delayed cleanup cannot make an expired request actionable.

### M2 command outcomes and stable problems

All entries are planned. Common malformed syntax and authentication outcomes
remain 400 and 401. Required missing, malformed, and stale version preconditions
return 428 `PRECONDITION_REQUIRED`, 400 `INVALID_PRECONDITION`, and 412
`PRECONDITION_FAILED`. Validation failures use 422 `VALIDATION_FAILED`;
changed input under a used key uses 409 `IDEMPOTENCY_KEY_REUSED`.

| Command | Success | Authorization and domain failures |
| --- | --- | --- |
| Create provider | 201 with provider ETag and Location | 422 validation; 409 key reuse |
| Read provider | 200 with provider ETag | Missing provider or inactive staff: 404 `PRIVATE_RESOURCE_NOT_FOUND` |
| Replace profile | 200 with new provider ETag | Inactive staff: 404 private resource; active non-admin: 403 `FORBIDDEN_ROLE`; precondition errors |
| Submit verification | 200 with new provider ETag | Inactive staff: 404 private resource; non-admin: 403 `FORBIDDEN_ROLE`; wrong source state: 409 `INVALID_PROVIDER_STATE` |
| Decide, suspend, restore provider | 200 with new provider ETag | Missing operator role: 403 `FORBIDDEN_PLATFORM_ROLE`; missing target: 404 private resource; wrong source state: 409 `INVALID_PROVIDER_STATE` |
| Finalize requirements | 201 with Location and unchanged plan ETag | Outsider: 404 private resource; non-organizer: 403 `FORBIDDEN_ROLE`; disallowed plan state: 409 `INVALID_PLAN_STATE`; invalid window or deadline: 422 validation |
| Publish or replace request | 201 with Location and new plan ETag | Outsider or foreign finalization: 404 private resource; non-organizer: 403 `FORBIDDEN_ROLE`; plan state: 409 `INVALID_PLAN_STATE`; stale finalization: 409 `FINALIZATION_VERSION_CHANGED`; zero matches: 409 `NO_ELIGIBLE_PROVIDERS`; excessive audience: 409 `RECIPIENT_LIMIT_EXCEEDED`; elapsed deadline: 409 `REQUEST_DEADLINE_EXPIRED` |
| Read current request or history | 200 | Outsider, absent plan, or absent current request: 404 private resource; bad cursor: 400 `INVALID_CURSOR` |
| Provider detail or feed | 200 | Invisible provider/request or stale eligibility: 404 `PRIVATE_RESOURCE_NOT_FOUND`; malformed or wrong-provider cursor: 400 `INVALID_CURSOR` |
| Close request | 200 with new plan ETag | Outsider: 404 private resource; non-organizer: 403 `FORBIDDEN_ROLE`; non-OPEN request: 409 `INVALID_REQUEST_STATE`; precondition errors |
| Cancel plan | 200 with new plan ETag | Outsider: 404 private resource; non-organizer: 403 `FORBIDDEN_ROLE`; disallowed state: 409 `INVALID_PLAN_STATE`; precondition errors |

For new plan commands, ETag checks precede current plan-state checks under the
lock; exact completed replay precedes both. Failed publication changes no
request, recipient, plan, audit, outbox, or idempotency state.

### Later command idempotency

M3 also requires command keys for offer submission and withdrawal, selection,
and match confirmation, decline, completion, and cancellation. Simulated
subscription commands use keys when that deferred extension exists.

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

## Offer APIs (M3, planned)

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

## Match APIs (M3, planned)

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

Implemented operational endpoints:

- `GET /actuator/health/liveness` reports application liveness only.
- `GET /actuator/health/readiness` reports application readiness and PostgreSQL
  readiness.
- `GET /actuator/prometheus` is available only in the `local`, `compose`, and
  `test` profiles.

Health is exposed in every profile with details hidden. No other actuator
endpoint is exposed.

Planned operational endpoints:

- M4 outbox and inbox backlog summaries for authorized operators
- M4 redrive command for a failed notification after operator review

Provider verification commands (M2, planned):

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
| 428 | Required version precondition is missing |
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
