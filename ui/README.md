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

Run npm run test:ci and npm run build.
