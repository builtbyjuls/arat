# Arat web client

This independent Angular workspace is the UI1 web client scaffold. It uses
Node 24 LTS, npm, standalone components, strict TypeScript and template
checking, SCSS, the Angular router, HttpClient, and Vitest.

## Development

Install the exact dependency graph with npm ci, then start the development
server with npm start.

The development server proxies /api and /v3 to the local backend at
http://localhost:8080. The proxy is not used by production builds.

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
  browser reports afterwards.
- `npm run check` runs lint, the covered test suite, and the production build.

The frontend CI job runs the same independent commands from `ui/` in this
order: `npm ci`, `npm run lint`, `npm run test:ci`, and `npm run build`.
Frontend verification does not invoke Maven.

Coverage thresholds are currently zero because the scaffold has only a smoke
test. Every feature card must add focused tests with its production code, and
the thresholds should increase when the application has enough behavior for a
meaningful baseline. Coverage and build output are local artifacts and must
remain untracked.
