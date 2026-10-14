# syntax=docker/dockerfile:1.7
#
# The Praxis Chess app image: the Spring Boot backend, the built frontend it
# serves, and Stockfish. Build from the repository root:
#
#   docker build -f docker/Dockerfile -t praxis-chess .
#
# Four stages, so a frontend change does not rebuild Stockfish and a backend
# change does not reinstall npm packages.

ARG STOCKFISH_TAG=sf_19

# ── 1. frontend ──────────────────────────────────────────────────────────────
FROM node:22-bookworm-slim AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ── 2. backend JAR ───────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS jar
WORKDIR /src/backend
COPY backend/pom.xml ./
# Dependencies first: this layer survives source edits.
RUN mvn -B -q -Dskip.npm -Dskip.installnodenpm dependency:go-offline || true
COPY backend/src ./src
# A developer's real application.yml holds secrets. .dockerignore keeps it out;
# this makes sure, because a pushed image would publish it.
RUN if [ -f src/main/resources/application.yml ]; then \
      echo "ERROR: backend/src/main/resources/application.yml is in the build context." >&2; \
      echo "       It holds local secrets and must not be baked into an image." >&2; \
      exit 1; \
    fi
# The frontend comes from stage 1; the pom's resources step copies
# ../frontend/dist into the JAR, so it goes exactly there.
COPY --from=web /src/frontend/dist /src/frontend/dist
RUN mvn -B -DskipTests -Dskip.npm -Dskip.installnodenpm package \
 && cp target/praxis-chess-*.jar /app.jar

# ── 3. Stockfish ─────────────────────────────────────────────────────────────
# Built from the pinned release tag. The "universal" targets compile every CPU
# variant and pick the fastest one at run time, so one image runs on any
# x86-64 or ARM64 machine without "illegal instruction" crashes.
FROM debian:bookworm-slim AS stockfish
ARG STOCKFISH_TAG
ARG TARGETARCH
RUN apt-get update \
 && apt-get install -y --no-install-recommends g++ make git curl wget ca-certificates \
 && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch "${STOCKFISH_TAG}" https://github.com/official-stockfish/Stockfish.git /sf
WORKDIR /sf/src
RUN case "${TARGETARCH:-amd64}" in \
      amd64) SF_ARCH=x86-64-universal ;; \
      arm64) SF_ARCH=arm64-universal ;; \
      *) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && make -j"$(nproc)" build ARCH="${SF_ARCH}" \
 && { strip stockfish || true; } \
 && ./stockfish quit </dev/null >/dev/null \
 && mkdir -p /out/doc \
 && cp stockfish /out/stockfish \
 && cp ../Copying.txt /out/doc/COPYING \
 && printf 'Stockfish %s, built from https://github.com/official-stockfish/Stockfish/tree/%s\nLicence: GNU GPL v3 (COPYING). Source: the URL above.\n' \
      "${STOCKFISH_TAG}" "${STOCKFISH_TAG}" > /out/doc/SOURCE

# ── 4. runtime ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
ARG STOCKFISH_TAG
LABEL org.opencontainers.image.title="Praxis Chess" \
      org.opencontainers.image.description="Local-first chess coach: Stockfish analysis, checked explanations, drills." \
      org.opencontainers.image.source="https://github.com/Praxis-Chess/Praxis-Chess" \
      org.opencontainers.image.licenses="MIT AND GPL-3.0-only"
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 praxis
COPY --from=stockfish /out/stockfish /usr/local/bin/stockfish
COPY --from=stockfish /out/doc /usr/share/doc/stockfish
COPY --from=jar /app.jar /app/app.jar
COPY docker/app/application.yml /app/config/application.yml
ENV STOCKFISH_VERSION=${STOCKFISH_TAG} \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50"
WORKDIR /app
USER praxis
EXPOSE 8086
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8086/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
