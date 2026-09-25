# Arat web client

This independent Angular workspace is the implemented UI1 responsive web
client. It uses Angular 22.2, Node `>=24.15.0 <25.0.0`, npm, standalone
components, strict TypeScript and template
checking, SCSS, the Angular router, HttpClient, and Vitest.

## Development

Install the exact dependency graph and generate the ignored API types, then
start the local-demo development server:

```text
npm ci
npm run generate:api-types
npm run ng -- serve --configuration local-demo
```

The development server proxies /api and /v3 to the local backend at
http://localhost:8080. The proxy is not used by production builds. The normal
development configuration intentionally has no local actor selector, so use
`local-demo` only for this local demonstration.

Start PostgreSQL and Spring from the repository root before starting the
development server:

```text
docker compose up --wait postgres
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

## Local demonstration

Use the `local-demo` selector only for the five fixed fake actors. It is not
production authentication. Use this executable manual sequence after starting
the Compose stack and opening `http://127.0.0.1:4200`:

1. Select Dani Provider. On Providers, create `Demo BGC Courts` with category
   `COURT` and area `BGC`. On its detail page, leave `COURT` and `BGC` selected,
   submit one evidence reference such as `registry:demo-bgc-courts`.
2. Select Owen Operator. Open Provider verification review, choose that exact
   `Demo BGC Courts` submission, and confirm acceptance.
3. Select Ari Organizer. Create `Demo BGC group`, then create an invitation for
   Bea's fixed account ID `10000000-0000-4000-8000-000000000002`. Copy the
   displayed token before changing actors; it is deliberately cleared on
   navigation or reload.
4. Select Bea Member, open Invitation acceptance, paste the copied token, and
   accept it. Select Ari again, open the group, and create a `COURT` plan for
   `BGC` with one candidate window starting at least 30 days from now. Set its
   end two hours later and use the plan time zone. Select Bea and save a
   preference for that window.
5. Select Ari, set an offer deadline at least seven days before that candidate
   window, finalize the requirements, and confirm publication. Select Dani and
   open the provider request feed to read the recipient-safe request. Cruz
   Outsider and an unrelated provider have no access to that request.

The automated browser journey covers this provider-to-publication path. Request
replacement, history, closure, and cancellation are verified by backend HTTP
and PostgreSQL checks, not by that browser journey.

The client preserves ETags and idempotency keys. A `412` keeps unsaved input
in memory, refreshes authoritative state, and requires deliberate reapplication.
`409` command conflicts are shown without automatic overwrite or retry. The
provider feed is a privacy-reduced projection: it never shows private group or
member data, and provider-safe organizer text has no automatic PII redaction.

## Containerized local demo

From the repository root, start the same-origin local-demo web path with:

```text
docker compose up --build --wait web
```

Open `http://127.0.0.1:4200`. The non-root web container serves the optimized
Angular build and proxies `/api` and `/v3` to the Spring service without CORS.
Set `ARAT_WEB_PORT` to change the loopback host port.

The web Dockerfile defaults to the production Angular configuration, which
excludes fake tokens and the local actor selector. Compose explicitly selects
`local-demo`; it is for local demonstration only and is not an Internet
deployment or production identity mechanism. Build the production-safe image
directly with:

```text
docker build --tag arat-ui:production ui
```

Run `scripts/smoke-ui.sh` from the repository root for the isolated web smoke
check. It uses a unique Compose project and removes its containers, network,
volumes, and local images on both success and failure.

## API types

`openapi/arat-v1.json` is the reviewed M0-M2 contract snapshot. It is exported
from the executable Spring contract under the explicit `test,local` profiles,
so it includes the local-only `GET /api/v1/dev/whoami` operation. That
operation is absent outside the local profile and is not a production API.

Run `npm run generate:api-types` to generate schema types from the snapshot.
The command does not contact a running backend and writes to the ignored
`src/app/api/generated/` directory. Tests and builds regenerate the types
before they run. HTTP calls remain handwritten so they can preserve status,
ETag, Location, and correlation headers.

Angular 22.2 requires the workspace's pinned TypeScript 6 release, while the
generator currently declares a TypeScript 5 peer range. The package-specific
npm override binds only the generator to the workspace compiler; clean type
generation and Angular compilation verify that compatibility.

From the repository root, refresh the reviewed snapshot deliberately with:

```text
./mvnw -Dit.test=OpenApiSnapshotIT \
  -Darat.openapi.snapshot.update=true verify
```

Normal Maven verification compares the normalized local-profile contract with
the committed snapshot and fails on drift.

## Verification

Run these commands from this directory:

- `npm ci` installs the exact dependency graph from the lockfile.
- `npm run generate:api-types` generates untracked TypeScript schema types from
  the committed snapshot without a running backend.
- `npm run lint` checks TypeScript and Angular templates, including the
  accessibility rules.
- `npm run test:ci` runs the Vitest suite and writes local coverage output to
  `coverage/`.
- `npm run build` creates the production build in the ignored `dist/`
  directory.
- `npm run test:browser:compose` starts an isolated local-demo Compose stack,
  runs the focused Chromium and accessibility checks, and removes the stack and
  browser reports afterwards. On a fresh machine, first run
  `npx playwright install --with-deps chromium`.
- `npm run check` runs lint, the covered test suite, the production build, and
  the production fake-local-actor exclusion check.

The frontend CI job runs the same independent commands from `ui/` in this
order: `npm ci`, `npm run lint`, `npm run test:ci`, and `npm run build`.
Frontend verification does not invoke Maven.

Coverage thresholds are currently zero because the scaffold has only a smoke
test. Every feature card must add focused tests with its production code, and
the thresholds should increase when the application has enough behavior for a
meaningful baseline. Coverage and build output are local artifacts and must
remain untracked.
