# Arat web client

This independent Angular workspace is the UI1 web client scaffold. It uses
Node 24 LTS, npm, standalone components, strict TypeScript and template
checking, SCSS, the Angular router, HttpClient, and Vitest.

## Development

Install the exact dependency graph with npm ci, then start the development
server with npm start.

The development server proxies /api and /v3 to the local backend at
http://localhost:8080. The proxy is not used by production builds.

## Verification

Run these commands from this directory:

- `npm ci` installs the exact dependency graph from the lockfile.
- `npm run lint` checks TypeScript and Angular templates, including the
  accessibility rules.
- `npm run test:ci` runs the Vitest suite and writes local coverage output to
  `coverage/`.
- `npm run build` creates the production build in the ignored `dist/`
  directory.
- `npm run check` runs lint, the covered test suite, and the production build.

Coverage thresholds are currently zero because the scaffold has only a smoke
test. Every feature card must add focused tests with its production code, and
the thresholds should increase when the application has enough behavior for a
meaningful baseline. Coverage and build output are local artifacts and must
remain untracked.
