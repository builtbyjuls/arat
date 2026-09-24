# Mobile Web Client Contract

Status: UI1 decisions accepted; supporting discovery reads are implemented and
the client is in progress.
M0-M2 backend behavior is implemented. UI1 precedes M3 without renumbering it.
This is a focused demonstration client, not a production-ready identity or
frontend platform, and it is a responsive web application, not a native app.

## Scope and authority

UI1 exposes the existing local journey:

```text
local actor -> private group -> collaborative plan -> finalized requirement
-> immutable provider request -> eligible provider feed
```

Spring and PostgreSQL remain authoritative for identity, current membership,
roles, privacy, workflow state, versions, eligibility, and deadlines. Guards,
role labels, validation, and disabled controls are usability aids only. UI1
changes no M0-M2 business invariant. The [API Contract](api-contract.md) owns
HTTP and field semantics; the [Domain Model](domain-model.md) and
[Consistency and Concurrency](consistency-and-concurrency.md) own transitions
and transaction rules.

Offers, offer votes, selection, provider confirmation, and matches remain M3.
Notification delivery remains M4; pending outbox rows do not mean a provider
was notified. UI1 excludes listings, billing, maps, geolocation, chat,
notifications, production identity, offline mode, and a custom design system.
Invitation revocation, member removal, leave, organizer transfer, suspension,
and restoration remain API-only. Provider staff administration is excluded;
M2 has no staff invitation or removal endpoints.

## Client and runtime boundary

- Angular 22, Node 24 LTS, and npm in an independent `ui/` workspace.
- Standalone components, strict TypeScript and template checking, Angular
  router, HttpClient, reactive forms, signals for local view state, and small
  services. Lazy feature routes keep the shell separate from domain screens.
- Project-owned SCSS and native semantic controls. No NgRx, Angular Material,
  other component library, SSR, PWA, or WebSockets.
- Vitest for unit and component tests; Playwright with an axe integration for
  browser and accessibility checks. Pin resolved dependencies in the npm
  lockfile and use reproducible installs.
- A checked-in executable OpenAPI snapshot, including the local-only identity
  probe, supplies deterministically generated TypeScript types. Generated types
  stay untracked and are regenerated before verification. Handwritten HTTP
  adapters expose response semantics; do not generate a client that hides them.
- Maven verification must not install Node or run the frontend; frontend
  verification must not run Maven. Backend contract export/drift checks and
  browser stack orchestration are explicit separate verification steps.

Development uses the Angular proxy for `/api` and `/v3`. The planned Compose
web container serves static assets and proxies those paths to Spring on the
same browser origin, preserving status, ETag, Location, and correlation headers.
No broad CORS configuration is needed. HTML deep links return the SPA, while
missing static assets and API errors must not return SPA HTML.

The web image uses pinned build/runtime images and a non-root static runtime.
Its default build is production-safe; local Compose explicitly selects the
optimized `local-demo` configuration. This is a production-shaped serving
boundary, not an Internet deployment or production-auth claim. The backend
remains one modular monolith and one PostgreSQL database. Cloud deployment,
TLS infrastructure, and a CDN are outside UI1.

## Browser routes and ownership

These are browser routes, distinct from `/api/v1` resources. All are planned.
Forms and detail sections listed within a workspace need no additional public
route. Unknown routes have an accessible not-found view.

| Route | Owner and purpose |
| --- | --- |
| `/` | Shell and local actor entry; navigate to the group index after verified local identity |
| `/groups` | Groups: current actor's private group index and creation form |
| `/groups/:groupId` | Groups: private detail, active members and roles, invitation creation |
| `/invitations/accept` | Groups: token-free paste form with explicit acceptance |
| `/groups/:groupId/plans` | Planning: group-scoped plan list and creation form for any active member |
| `/plans/:planId` | Planning workspace: private detail, requirements, own preference, member preference summary, finalizations, publication, request history, closure, and cancellation |
| `/providers` | Providers: active-staff workspace chooser and provider creation form |
| `/providers/:providerId` | Providers: staff-private detail, ADMIN profile replacement and verification submission |
| `/providers/:providerId/requests` | Marketplace: recipient feed and allowlisted request detail in the explicit provider context |
| `/operations/provider-verifications` | Providers operations: operator-only pending queue and exact-submission accept/reject controls |

`/plans/:planId` is canonical because the existing plan detail projection has
no group ID. Do not introduce a nested group/plan detail route, infer a group
relationship from a URL, or require a cached group ID for a direct reload. A
fresh plan read authorizes the resource. Organizer actions are labeled as such;
when current scoped role evidence is unavailable, do not invent a role from the
local persona name. The server still decides attempted commands and returns
its role failures. Group navigation can return to `/groups` without a guessed
parent. Provider detail will expose `callerStaffRole` for reload-safe UI hints.

Each list supports bounded cursor paging, loading, empty, error, and explicit
refresh states. Cursors are opaque and reset on actor or resource-context
changes. A private 404 says only that the resource is unavailable; it must not
distinguish an unknown ID from revoked or unrelated access.

## Journeys

### Organizer and member

An organizer creates a group, reads its members, and creates an account-bound
invitation using a known invitee account ID and bounded expiry. There is no
account directory, email delivery, or persistent invitation inbox. Show the
returned raw token transiently with an explicit copy action and safe manual
handoff guidance. Clear it on route exit, actor change, or reload.

The intended member opens `/invitations/accept`, pastes the token, and explicitly
accepts. Never place it in an Angular path, query, fragment, navigation state,
or browser history. The existing API still uses
`POST /api/v1/group-invites/{token}/accept`; the HTTP adapter sends that request
without navigating to it. Keep the token only in the form long enough to send,
then clear it on success, failure, route exit, or actor change. After an
ambiguous transport failure, a deliberate retry requires repasting the same
token and reuses the same intent key; it must not retain the raw token as retry
state. Success navigates using the returned group representation. Unavailable,
wrong-account, expired, revoked, and consumed tokens receive the same safe
unavailable presentation. Proxy logging and client diagnostics must redact or
omit the token-bearing API path and Authorization values.

Any active member can create a plan from its group's list. A member reads the
private plan, records their own availability, attendance, guests, budget and
ranked preferences, and sees current/stale member input. An organizer replaces
requirements, chooses one active window and a deadline, then reviews the
immutable finalization and its aggregate warnings before explicit publication.
Preferences are advisory, never automatic headcount, budget, schedule, or votes.

Render times in the plan's IANA time zone, send explicit-offset instants, and
retain the API's exact two-decimal PHP strings without floating-point money
conversion. Finalization requires database `now < offerDeadline < chosenStart`
and PostgreSQL microsecond precision. Browser validation cannot authorize a
deadline. Finalization does not change plan state/version; requirement edits
make its basis stale. On reload, page finalization history to rediscover the
newest candidate matching the current plan version, label historical entries,
and recheck with the server on publication.

Publication creates an immutable version and a fixed eligible audience. Show
zero-recipient, cap, elapsed-deadline, and stale-basis conflicts explicitly.
Draft and preference edits while N is open remain private. Direct publication
of N+1 supersedes N atomically without requiring closure first. Display plan
state separately from request lifecycle and database-evaluated actionability.
There is no UI1 offer submission even when a request is actionable.

Members can inspect current and historical request snapshots. An organizer
explicitly confirms closing an OPEN request or cancelling a plan. Closure
returns the plan to collaboration; cancellation is terminal. Both preserve
history and require the current plan ETag. Refresh authoritative plan/request
state after success; never imply that closing and cancelling are equivalent.

### Provider and operator

A local actor creates an UNVERIFIED provider and becomes its active ADMIN.
The provider chooser lists only active staff contexts; the selected route
always names the provider. ADMIN can replace the profile and submit 1-10
bounded opaque evidence references from UNVERIFIED or REJECTED. STAFF may read
but not use ADMIN commands. Display verification states precisely, including
PENDING, VERIFIED, REJECTED, and SUSPENDED. Verification is an operator review,
not a safety, quality, licensing, or availability guarantee.

An operator discovers exact pending submissions from the bounded review queue,
reads the provider summary, timestamp, submission ID and evidence references,
and explicitly confirms accept or reject with an optional bounded note.
Evidence references are plain text, not links, HTML, uploads, or automatically
fetched documents. Each decision submits the exact displayed submission ID;
a stale row cannot decide a resubmission. Remove a row only after success and
refresh the queue. No bulk decisions or suspension/restoration UI is included.

Eligible active staff can browse the provider-bound request feed and its
allowlisted detail. Feed pages may contain authorized terminal history; show
state, deadline, and the server's effective actionable flag. Do not add category
or area filters. VERIFIED state alone grants no request access: active staff,
ACTIVE recipient, and captured/current eligibility equality are required.
Restoration never revives an old grant. A platform operator role does not imply
provider staff or private group membership.

## Required discovery reads

The reload-safe reads and operator queue are implemented in their owning
modules without querying foreign module tables directly.

| Read | Required projection and privacy |
| --- | --- |
| `GET /api/v1/groups` | Implemented: compact groups and caller's scoped role for ACTIVE membership only; no public search, inactive history, plan data, or foreign actor filter |
| `GET /api/v1/providers` | Implemented: provider ID, display name, verification status, version, caller staff role, categories and service areas for active staff only; staff-private provider detail also exposes `callerStaffRole` for direct reloads |
| `GET /api/v1/plans/{planId}/requirement-finalizations` | Implemented: existing immutable finalization representation plus `currentBasis`; active group members only, same private 404 as plan reads; no preference bodies, recipients, audit, or outbox data |
| `GET /api/v1/operations/providers/pending-verifications` | Implemented: PLATFORM_OPERATOR only; exact current submission ID, provider ID/version, display name, PENDING status, categories, service areas, submission time and ordered evidence references; excludes superseded/decided submissions |

Use `{items, nextCursor}`, default limit 20 and maximum 100, and stable opaque
versioned cursors scoped to the actor/context. Group/provider indexes order by
resource creation time descending then resource ID descending; finalizations
order by creation time descending then finalization ID descending; the queue
orders by submission time descending then submission ID descending. Ordinary
profile/draft edits must not reorder rows. Reject malformed or wrong-context
cursors with `400 INVALID_CURSOR`; validate limits as in existing list APIs.
Authorization and pending-state predicates apply before cursor and limit logic.
Indexes need actual PostgreSQL query-plan evidence. Nonmembers discover no
private contexts; authenticated nonoperators receive
`403 FORBIDDEN_PLATFORM_ROLE` without queue rows.

Finalizations expose no preference bodies, recipients, audit, or outbox data.
An operator queue read grants no decision authority; the existing locked
provider state and exact submission ID remain the fence. A queue refresh after
a committed decision must not present that decided submission as current.
Existing group plan lists and request history/feed already provide the other
reload paths; browser storage must not replace these server reads.

## HTTP and command state

Adapters return body, HTTP status, ETag, Location, and `X-Correlation-Id`
explicitly, including successful responses with no body. Missing optional
headers are handled without fabrication; a missing required command version
must trigger a read, not a guessed ETag. Use returned identifiers and Location
according to each resource contract, mapping API locations to browser routes.
Never navigate blindly to an API Location, especially an invitation resource.

Keep quoted ETags exactly as received and scoped to actor and resource. Plan,
group, provider, and preference versions are different resources. Finalization,
publication, closure, and cancellation use the plan ETag; profile replacement
uses the provider ETag. First preference PUT sends `If-None-Match: *`; subsequent
PUTs use the preference ETag plus the current `basisPlanVersion`. Requirement
replacement uses only the plan precondition. Do not add command keys to
replacement APIs that use preconditions alone.

For keyed commands create one key per deliberate user intent and retain its
exact route, body and preconditions in memory for the active attempt, with the
invitation-token exception above. Prevent duplicate clicks. A transport retry
of the same attempt uses the same key and unchanged command; changed input or
a deliberately reapplied action is a new intent. Mutations are never retried
automatically with a new key or after an application response. Reads may retry
only bounded transient failures. A reload discards volatile intent state: first
rediscover server state and never silently resubmit an uncertain mutation.

Exact replay may return the original ETag and representation even after later
changes, including an original OPEN publication now closed or superseded. Keep
that command result distinct from current state and perform fresh authorized
reads before further actions. Do not overwrite current state with old replay
metadata. A `412` is visible: preserve unsaved input in memory, refresh, and let
the user deliberately reapply; never merge or overwrite automatically. Treat
`409 REQUIREMENT_VERSION_CHANGED`, `FINALIZATION_VERSION_CHANGED`, and
`IDEMPOTENCY_KEY_REUSED` as explicit conflicts. A `428` requires acquiring the
missing precondition, not bypassing it.

Map `application/problem+json` into a typed problem with code, status, safe
text, field violations, and correlation ID. Associate validation errors with
controls, give a bounded unknown-error fallback, and render all server/user
prose as text, never trusted HTML. Do not display raw error URLs, exception
names, SQL, stack traces, or request bodies. Make the response correlation ID
available for diagnosis, falling back to the problem's `correlationId` when
needed; never include invitation tokens or evidence in diagnostic output.

## Local identity, restoration, and privacy

The `local-demo` build alone offers the five fixed actors documented in the
[local authentication contract](api-contract.md#local-authentication): Ari,
Bea, Cruz, Dani, and Owen. Show an unmistakable local-development label. Store
only the selected persona identifier in session storage, not its bearer token,
resource IDs, ETags, drafts, evidence, invitation tokens, or command state.
No domain data goes into local storage or IndexedDB.

Before rendering actor-scoped content on startup or restoration, validate the
selection with `GET /api/v1/dev/whoami`, including expected account and roles.
Invalid selection, a missing probe, or failed identity validation produces a
bounded unavailable state and fails closed. On actor change, immediately clear
all scoped data, forms, evidence, ETags, pagination and intent state, discard
late responses from the old actor, and navigate to a safe root. Never reuse
one actor's request or command state for another. A 401 invalidates the active
identity and clears private views; 403/404 discard inaccessible resource data
without guessing its existence.

On a valid same-actor reload, restore only identity, then fetch the route's
resources from the server. A fresh session can discover groups, group plans,
provider contexts, finalizations, and published requests through their indexes
without pasted domain UUIDs. Invitation acceptance intentionally requires a
manually handed-off token; invitation creation uses a known local account ID.

The normal production configuration and output contain neither the switcher
nor fake tokens and provide no embedded bearer credential. Production has no
identity adapter and fails closed. UI1 adds no login, registration, OAuth,
refresh-token, or production session mechanism. The local selector is not
impersonation or proof of production authentication.

Provider views never fetch or render private group/member data, preferences,
attendance, plan titles, recipient sets, eligibility internals, audit or outbox
rows. Provider-safe notes, must-haves and string attributes are intentionally
publishable organizer text; the UI must explain that there is no automatic PII
redaction. Keep private notes clearly separate from publishable fields. Neither
client nor proxy logs may record tokens, Authorization values, evidence
references, or private form bodies. Browser traces containing sensitive test
inputs stay untracked and must be handled as sensitive diagnostics.

## Mobile and accessibility acceptance

- No horizontal page overflow from 320 CSS pixels upward. Verify primarily at
  360 and 390 pixels; enhance for tablet and desktop after the narrow layout.
- Keep primary controls at least 44 by 44 CSS pixels where practical. Long IDs,
  evidence strings, validation text, and request content wrap within the page.
- Use semantic landmarks, ordered headings, explicit labels, field-error
  associations, visible focus, keyboard operation and predictable focus after
  navigation or dialogs. Loading, success, and errors must be perceivable.
- Avoid hover-only behavior, color-only meaning, and content lost at browser
  zoom. Destructive or irreversible-looking actions require explicit
  confirmation with a clear cancel path and focus restoration.
- Front-facing project copy is ASCII-only; display legitimate user content
  safely without imposing an ASCII restriction on domain fields that allow
  Unicode. Icons need ASCII accessible names.

## Evidence and exit gate

The [Testing Strategy](testing-strategy.md#ui1-web-client-gates-planned) defines
the independent frontend lane and browser checks. Before UI1 is called
implemented, a clean checkout must pass backend PostgreSQL verification,
OpenAPI drift/type generation, frontend lint/unit/component tests, normal and
local-demo builds, production fake-token exclusion, isolated same-origin
Compose smoke, and Playwright/accessibility checks.

The full cross-persona journey runs at 390 CSS pixels. Representative shell,
list, form, detail, confirmation, and error patterns must also pass at 320 and
360, plus a restrained desktop smoke. Demonstrate reload-safe discovery,
conflict/retry handling, provider verification and recipient privacy, request
replacement/history/closure/cancellation, and actor-switch isolation. Browser
tests supplement PostgreSQL invariants; they do not replace them. Automated
accessibility checks supplement keyboard, focus, zoom, and manual review.
Until this evidence exists, UI1 remains planned and M3 is the next core backend
milestone after it.
