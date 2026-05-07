# syntax=docker/dockerfile:1.6
FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradlew ./
COPY gradle ./gradle
COPY services/circleguard-notification-service ./services/circleguard-notification-service
RUN ./gradlew :services:circleguard-notification-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd -r -u 1001 -g root circleguard
COPY --from=build /workspace/services/circleguard-notification-service/build/libs/*.jar app.jar
USER 1001
ENV SERVER_PORT=8084
EXPOSE 8084
# Container-level healthcheck is delegated to Kubernetes liveness/readiness
# probes (see k8s/base/services.yaml). Adding wget/curl to the JRE image
# would violate the minimal-image principle.
ENTRYPOINT ["java","-XX:+UseContainerSupport","-jar","/app/app.jar"]
