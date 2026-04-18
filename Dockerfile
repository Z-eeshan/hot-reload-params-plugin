# =============================================================================
# Multi-stage Dockerfile for building the Hot Reload Parameters Jenkins Plugin
#
# Usage:
#   docker build -t hot-reload-params-builder .
#   docker run --rm -v "$(pwd)/out:/out" hot-reload-params-builder
#
# The .hpi and .jar artifacts are copied to the mounted /out directory.
# =============================================================================

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy the POM first to cache dependency resolution as a separate layer
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src/ src/
RUN mvn clean package -B -DskipTests

# ── Stage 2: Export ─────────────────────────────────────────────────────────
FROM alpine:3.20

COPY --from=builder /build/target/hot-reload-params.hpi /artifacts/
COPY --from=builder /build/target/hot-reload-params.jar /artifacts/

VOLUME /out

CMD ["sh", "-c", "cp /artifacts/* /out/ && echo '==> Artifacts exported to /out:' && ls -lh /out/hot-reload-params.*"]
