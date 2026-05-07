#!/usr/bin/env bash
# Builds Docker images from pre-built JARs (fast path, no Gradle inside Docker).
# Usage: ./ci/scripts/02-build-images.sh [dev|stage|prod]
set -euo pipefail

TAG="${1:-dev}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

declare -A PORTS=( [auth]=8081 [identity]=8083 [promotion]=8082 [notification]=8084 [form]=8085 [gateway]=8086 )

for s in "${!PORTS[@]}"; do
  port="${PORTS[$s]}"
  jar=$(find "services/circleguard-${s}-service/build/libs" -name "*.jar" ! -name "*plain*" 2>/dev/null | head -1)

  if [ -z "$jar" ]; then
    echo "[SKIP] No JAR found for $s (run ./gradlew bootJar first)"
    continue
  fi

  echo "[build] circleguard/${s}-service:${TAG}  (jar=$(basename $jar))"

  # Inline Dockerfile — copies the pre-built JAR, no Gradle needed
  docker build \
    --build-arg JAR_FILE="$jar" \
    --build-arg PORT="$port" \
    -t "registry.local:5000/circleguard/${s}-service:${TAG}" \
    -t "circleguard/${s}-service:${TAG}" \
    -f - . <<'DOCKERFILE'
ARG JAR_FILE
ARG PORT=8080
# Uses the maven:eclipse-temurin-17 image already present in the local cache
# so no network download is needed.  Java 17 runs Spring Boot 3 / Java 21
# compiled code without issues (bytecode compatibility).
FROM maven:3.9.9-eclipse-temurin-17
WORKDIR /app
ARG JAR_FILE
COPY ${JAR_FILE} app.jar
ARG PORT
ENV SERVER_PORT=${PORT}
EXPOSE ${PORT}
ENTRYPOINT ["java","-XX:+UseContainerSupport","-jar","/app/app.jar"]
DOCKERFILE

  echo "  -> built circleguard/${s}-service:${TAG}"
done

echo ""
echo "=== Images built ==="
docker images --format "{{.Repository}}:{{.Tag}}\t{{.Size}}" | grep "circleguard" | sort
