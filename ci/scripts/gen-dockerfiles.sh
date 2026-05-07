#!/usr/bin/env bash
# Regenerates Dockerfiles for all chosen services (idempotent).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
declare -A PORTS=( [auth]=8081 [identity]=8083 [promotion]=8082 [notification]=8084 [form]=8085 [gateway]=8086 )
for s in "${!PORTS[@]}"; do
  port="${PORTS[$s]}"
  out="$ROOT/services/circleguard-${s}-service/Dockerfile"
  cat > "$out" <<DOCKERFILE
# syntax=docker/dockerfile:1.6
FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradlew ./
COPY gradle ./gradle
COPY services/circleguard-${s}-service ./services/circleguard-${s}-service
RUN ./gradlew :services:circleguard-${s}-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd -r -u 1001 -g root circleguard
COPY --from=build /workspace/services/circleguard-${s}-service/build/libs/*.jar app.jar
USER 1001
ENV SERVER_PORT=${port}
EXPOSE ${port}
# Container-level healthcheck is delegated to Kubernetes liveness/readiness
# probes (see k8s/base/services.yaml). Adding wget/curl to the JRE image
# would violate the minimal-image principle.
ENTRYPOINT ["java","-XX:+UseContainerSupport","-jar","/app/app.jar"]
DOCKERFILE
  echo "Wrote $out"
done
