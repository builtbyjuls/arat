FROM node:24.15.0-alpine3.23 AS build

WORKDIR /build

RUN npm install --global npm@11.19.0

COPY package.json package-lock.json ./
RUN npm ci

COPY angular.json eslint.config.js tsconfig*.json ./
COPY openapi/ openapi/
COPY public/ public/
COPY scripts/ scripts/
COPY src/ src/

ARG ANGULAR_CONFIGURATION=production
RUN npm run build -- --configuration="$ANGULAR_CONFIGURATION" \
    && if [ "$ANGULAR_CONFIGURATION" = "production" ]; then \
        npm run verify:production-local-demo-exclusion; \
    fi

FROM nginxinc/nginx-unprivileged:1.29.5-alpine3.23

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /build/dist/ui/browser/ /usr/share/nginx/html/

USER 101:101

EXPOSE 8080
