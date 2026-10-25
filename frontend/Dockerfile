# syntax=docker/dockerfile:1

# Build: Node 22 (the CI version) compiles the Angular production bundle.
# Runtime: unprivileged nginx (uid 101, port 8080) serves it and reverse-proxies /api to the backend.

# ---- Build stage ----
FROM node:22.23.4-alpine3.24 AS build

# Playwright is only a devDependency for browser e2e tests; never fetch its browsers during npm ci.
# The production build needs neither Chrome nor Karma.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    NG_CLI_ANALYTICS=false \
    CI=true

WORKDIR /app

# Install dependencies in their own layer so source edits do not invalidate it.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY angular.json tsconfig.json tsconfig.app.json ./
COPY src ./src
RUN npm run build

# ---- Runtime stage ----
FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24

# Backend upstream and DNS resolver, rendered into conf.d/default.conf by the image's envsubst
# entrypoint (/etc/nginx/templates/*.template). The resolver is Docker's embedded DNS, so the
# backend name is resolved per request and nginx starts even when the backend is not up yet.
ENV BACKEND_HOST=backend \
    BACKEND_PORT=8080 \
    NGINX_RESOLVER=127.0.0.11

USER root
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/system-guide-forge/browser /usr/share/nginx/html
USER 101

EXPOSE 8080
