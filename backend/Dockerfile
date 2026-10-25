# syntax=docker/dockerfile:1

# Both stages use SapMachine (Docker official image) on Ubuntu 24.04 "noble", which Playwright 1.55
# supports for `install --with-deps`. Tags are pinned to 25.0.5 (>= 25.0.5 is required for CVE-2026-91403).

# ---- Build stage: compile and package the Spring Boot jar with the Maven wrapper ----
FROM sapmachine:25.0.5.0.2-ubuntu-noble AS build

# The project wrapper downloads Maven with curl and unpacks it with unzip.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Resolve Maven and the dependencies in their own layer so source edits do not invalidate it.
COPY mvnw pom.xml ./
# Strip CRLF (core.autocrlf=true checkouts on Windows) and make the wrapper executable.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN ./mvnw -B -DskipTests package \
    && cp target/backend-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --destination extracted

# ---- Runtime stage: JRE + Playwright Chromium, run as a non-root user ----
FROM sapmachine:25.0.5.0.2-jre-headless-ubuntu-noble

# Browsers live in a fixed, shared location instead of the installing user's cache directory.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

WORKDIR /app

# Extracted layout: app.jar (manifest classpath points to lib/) plus plain dependency jars.
COPY --from=build /workspace/extracted/lib ./lib
COPY --from=build /workspace/extracted/app.jar ./app.jar

# Install the Chromium headless shell and its OS dependencies with Playwright's own CLI from the app
# classpath, so the browser revision always matches the bundled Playwright Java version.
RUN java -cp "/app/app.jar:/app/lib/*" com.microsoft.playwright.CLI install --with-deps --only-shell chromium \
    && rm -rf /var/lib/apt/lists/* /tmp/* \
    && chmod -R a+rX "$PLAYWRIGHT_BROWSERS_PATH" \
    && useradd --create-home --uid 10001 --shell /usr/sbin/nologin sgf

# Browsers are baked in; never download at runtime. Playwright extracts its driver into java.io.tmpdir
# (/tmp, world-writable) on every Playwright.create(), which works for the non-root user.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

USER sgf

EXPOSE 8080

# Database settings, SGF_CREDENTIAL_KEY and SGF_ALLOW_DOCKER_HOST are provided at runtime; no defaults here.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
